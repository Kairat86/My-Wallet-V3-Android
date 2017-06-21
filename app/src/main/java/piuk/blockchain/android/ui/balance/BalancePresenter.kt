package piuk.blockchain.android.ui.balance

import info.blockchain.wallet.contacts.data.Contact
import info.blockchain.wallet.contacts.data.FacilitatedTransaction
import info.blockchain.wallet.contacts.data.PaymentRequest
import info.blockchain.wallet.multiaddress.TransactionSummary
import info.blockchain.wallet.payload.data.LegacyAddress
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import piuk.blockchain.android.R
import piuk.blockchain.android.data.access.AccessState
import piuk.blockchain.android.data.access.AuthEvent
import piuk.blockchain.android.data.contacts.ContactTransactionDateComparator
import piuk.blockchain.android.data.contacts.ContactTransactionModel
import piuk.blockchain.android.data.contacts.ContactsEvent
import piuk.blockchain.android.data.datamanagers.BuyDataManager
import piuk.blockchain.android.data.datamanagers.ContactsDataManager
import piuk.blockchain.android.data.datamanagers.PayloadDataManager
import piuk.blockchain.android.data.datamanagers.TransactionListDataManager
import piuk.blockchain.android.data.notifications.NotificationPayload
import piuk.blockchain.android.data.rxjava.RxBus
import piuk.blockchain.android.data.rxjava.RxUtil
import piuk.blockchain.android.injection.Injector
import piuk.blockchain.android.ui.account.ConsolidatedAccount
import piuk.blockchain.android.ui.account.ItemAccount
import piuk.blockchain.android.ui.base.BasePresenter
import piuk.blockchain.android.ui.base.UiState
import piuk.blockchain.android.ui.customviews.ToastCustom
import piuk.blockchain.android.ui.home.MainActivity
import piuk.blockchain.android.ui.onboarding.OnboardingPagerContent
import piuk.blockchain.android.ui.swipetoreceive.SwipeToReceiveHelper
import piuk.blockchain.android.util.*
import java.text.DecimalFormat
import java.util.*
import javax.inject.Inject

class BalancePresenter : BasePresenter<BalanceView>() {

    @Inject lateinit var exchangeRateFactory: ExchangeRateFactory
    @Inject lateinit var transactionListDataManager: TransactionListDataManager
    @Inject lateinit var contactsDataManager: ContactsDataManager
    @Inject lateinit var swipeToReceiveHelper: SwipeToReceiveHelper
    @Inject lateinit var payloadDataManager: PayloadDataManager
    @Inject lateinit var buyDataManager: BuyDataManager
    @Inject lateinit var stringUtils: StringUtils
    @Inject lateinit var prefsUtil: PrefsUtil
    @Inject lateinit var accessState: AccessState
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var appUtil: AppUtil

    private var contactsEventObservable: Observable<ContactsEvent>? = null
    private var notificationObservable: Observable<NotificationPayload>? = null
    private var authEventObservable: Observable<AuthEvent>? = null

    private var activeAccountAndAddressList: MutableList<ItemAccount> = mutableListOf()
    private var displayList: MutableList<Any> = mutableListOf()
    private var chosenAccount: ItemAccount? = null

    init {
        Injector.getInstance().dataManagerComponent.inject(this)
    }

    override fun onViewReady() {
        view.setUiState(UiState.LOADING)

        subscribeToEvents()
        storeSwipeReceiveAddresses()

        activeAccountAndAddressList = getAllDisplayableAccounts()
        chosenAccount = activeAccountAndAddressList[0]

        chosenAccount?.let {
            Observable.merge(
                    getBalanceObservable(it),
                    getTransactionsListObservable(it),
                    getUpdateTickerObservable()
            ).compose(RxUtil.addObservableToCompositeDisposable(this))
                    .subscribe(
                            { /* No-op */ },
                            { view.setUiState(UiState.FAILURE) })
        }
    }

    override fun onViewDestroyed() {
        contactsEventObservable?.let { rxBus.unregister(ContactsEvent::class.java, it) }
        notificationObservable?.let { rxBus.unregister(NotificationPayload::class.java, it) }
        authEventObservable?.let { rxBus.unregister(AuthEvent::class.java, it) }
        super.onViewDestroyed()
    }

    internal fun onAccountChosen(position: Int) {
        chosenAccount = activeAccountAndAddressList[position]
        chosenAccount?.let {
            Observable.merge(
                    getBalanceObservable(it),
                    getTransactionsListObservable(it)
            ).compose(RxUtil.addObservableToCompositeDisposable(this))
                    .subscribe(
                            { /* No-op */ },
                            { view.setUiState(UiState.FAILURE) })
        }
    }

    internal fun onRefreshRequested() {
        chosenAccount?.let {
            Observable.merge(
                    getBalanceObservable(it),
                    getTransactionsListObservable(it),
                    getFacilitatedTransactionsObservable()
            ).compose(RxUtil.addObservableToCompositeDisposable(this))
                    .subscribe(
                            { /* No-op */ },
                            { view.setUiState(UiState.FAILURE) })
        }
    }

    internal fun setViewType(isBtc: Boolean) {
        accessState.setIsBtc(isBtc)
        view.onViewTypeChanged(isBtc)
        view.onTotalBalanceUpdated(getBalanceString(isBtc, chosenAccount?.absoluteBalance ?: 0L))
    }

    internal fun invertViewType() {
        setViewType(!accessState.isBtc)
    }

    internal fun onResume() {
        // Here we check the Fiat and Btc formats and let the UI handle any potential updates
        val btcBalance = transactionListDataManager.getBtcBalance(chosenAccount?.accountObject)
        val balanceTotal = getBalanceString(accessState.isBtc, btcBalance)
        view.onTotalBalanceUpdated(balanceTotal)
        view.onViewTypeChanged(accessState.isBtc)
    }

    internal fun areLauncherShortcutsEnabled(): Boolean {
        return prefsUtil.getValue(PrefsUtil.KEY_RECEIVE_SHORTCUTS_ENABLED, true)
    }

    internal fun onPendingTransactionClicked(fctxId: String) {
        contactsDataManager.getContactFromFctxId(fctxId)
                .compose(RxUtil.addSingleToCompositeDisposable(this))
                .subscribe({
                    val transaction = it.facilitatedTransactions[fctxId]

                    if (transaction == null) {
                        view.showToast(R.string.contacts_transaction_not_found_error, ToastCustom.TYPE_ERROR)
                    } else {
                        when {
                            transaction.state == FacilitatedTransaction.STATE_WAITING_FOR_ADDRESS
                                    && transaction.role == FacilitatedTransaction.ROLE_RPR_INITIATOR ->
                                // Payment request sent, waiting for address from recipient
                                view.showWaitingForAddressDialog()

                            transaction.state == FacilitatedTransaction.STATE_WAITING_FOR_PAYMENT
                                    && transaction.role == FacilitatedTransaction.ROLE_PR_INITIATOR ->
                                // Payment request sent, waiting for payment
                                view.showWaitingForPaymentDialog()

                            transaction.state == FacilitatedTransaction.STATE_WAITING_FOR_ADDRESS
                                    && transaction.role == FacilitatedTransaction.ROLE_PR_RECEIVER ->
                                // Received payment request, need to send address to sender
                                showSendAddressDialog(fctxId)

                            transaction.state == FacilitatedTransaction.STATE_WAITING_FOR_PAYMENT
                                    && transaction.role == FacilitatedTransaction.ROLE_RPR_RECEIVER ->
                                // Waiting for payment
                                view.initiatePayment(
                                        transaction.toBitcoinURI(),
                                        it.id,
                                        it.mdid,
                                        transaction.id
                                )
                        }
                    }
                }, {
                    view.showToast(
                            R.string.contacts_transaction_not_found_error,
                            ToastCustom.TYPE_ERROR
                    )
                })
    }

    internal fun onPendingTransactionLongClicked(fctxId: String) {
        contactsDataManager.facilitatedTransactions
                .filter { it.facilitatedTransaction.id == fctxId }
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe({
                    val fctx = it.facilitatedTransaction

                    if (fctx.state == FacilitatedTransaction.STATE_WAITING_FOR_ADDRESS) {
                        when {
                            fctx.role == FacilitatedTransaction.ROLE_PR_RECEIVER ->
                                view.showTransactionDeclineDialog(fctxId)
                            fctx.role == FacilitatedTransaction.ROLE_RPR_INITIATOR ->
                                view.showTransactionCancelDialog(fctxId)
                        }
                    } else if (fctx.state == FacilitatedTransaction.STATE_WAITING_FOR_PAYMENT) {
                        when {
                            fctx.role == FacilitatedTransaction.ROLE_RPR_RECEIVER ->
                                view.showTransactionDeclineDialog(fctxId)
                            fctx.role == FacilitatedTransaction.ROLE_PR_INITIATOR ->
                                view.showTransactionCancelDialog(fctxId)
                        }
                    }
                }, { /* No-op */ })
    }

    internal fun onAccountChosen(accountPosition: Int, fctxId: String) {
        contactsDataManager.getContactFromFctxId(fctxId)
                .doOnSubscribe { view.showProgressDialog() }
                .doOnError { view.showToast(R.string.contacts_transaction_not_found_error, ToastCustom.TYPE_ERROR) }
                .flatMapCompletable { contact ->
                    val transaction = contact.facilitatedTransactions[fctxId]

                    val paymentRequest = PaymentRequest()
                    paymentRequest.intendedAmount = transaction?.intendedAmount ?: 0L
                    paymentRequest.id = fctxId

                    payloadDataManager.getNextReceiveAddressAndReserve(
                            payloadDataManager.getPositionOfAccountInActiveList(
                                    accountPosition), "Payment request ${transaction?.id}"
                    ).doOnNext { paymentRequest.address = it }
                            .flatMapCompletable {
                                contactsDataManager.sendPaymentRequestResponse(
                                        contact.mdid,
                                        paymentRequest,
                                        fctxId
                                )
                            }
                            .doAfterTerminate { view.dismissProgressDialog() }
                }
                .compose(RxUtil.addCompletableToCompositeDisposable(this))
                .subscribe(
                        {
                            view.showToast(R.string.contacts_address_sent_success, ToastCustom.TYPE_OK)
                            refreshFacilitatedTransactions()
                        },
                        { view.showToast(R.string.contacts_address_sent_failed, ToastCustom.TYPE_ERROR) })
    }

    internal fun confirmDeclineTransaction(fctxId: String) {
        contactsDataManager.getContactFromFctxId(fctxId)
                .flatMapCompletable { contactsDataManager.sendPaymentDeclinedResponse(it.mdid, fctxId) }
                .doOnError { contactsDataManager.fetchContacts() }
                .doAfterTerminate { this.refreshFacilitatedTransactions() }
                .compose(RxUtil.addCompletableToCompositeDisposable(this))
                .subscribe(
                        { view.showToast(R.string.contacts_pending_transaction_decline_success, ToastCustom.TYPE_OK) },
                        { view.showToast(R.string.contacts_pending_transaction_decline_failure, ToastCustom.TYPE_ERROR) })
    }

    internal fun confirmCancelTransaction(fctxId: String) {
        contactsDataManager.getContactFromFctxId(fctxId)
                .flatMapCompletable { contactsDataManager.sendPaymentCancelledResponse(it.mdid, fctxId) }
                .doOnError { contactsDataManager.fetchContacts() }
                .doAfterTerminate { this.refreshFacilitatedTransactions() }
                .compose(RxUtil.addCompletableToCompositeDisposable(this))
                .subscribe(
                        { view.showToast(R.string.contacts_pending_transaction_cancel_success, ToastCustom.TYPE_OK) },
                        { view.showToast(R.string.contacts_pending_transaction_cancel_failure, ToastCustom.TYPE_ERROR) })
    }

    fun isOnboardingComplete(): Boolean {
        // If wallet isn't newly created, don't show onboarding
        return prefsUtil.getValue(PrefsUtil.KEY_ONBOARDING_COMPLETE, false) || !appUtil.isNewlyCreated()
    }

    fun setOnboardingComplete(completed: Boolean) {
        prefsUtil.setValue(PrefsUtil.KEY_ONBOARDING_COMPLETE, completed)
    }

    fun getBitcoinClicked() {
        buyDataManager.canBuy
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe({
                    if (it ?: false) {
                        view.startBuyActivity()
                    } else {
                        view.startReceiveFragment()
                    }
                }, { it.printStackTrace() })
    }

    fun checkLatestAnnouncement(txList: List<TransactionSummary>) {
        // If user hasn't completed onboarding, ignore announcements
        buyDataManager.canBuy
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe({ buyAllowed ->
                    if (isOnboardingComplete() && buyAllowed!!) {
                        if (!prefsUtil.getValue(PrefsUtil.KEY_LATEST_ANNOUNCEMENT_DISMISSED, false)
                                && !txList.isEmpty()) {
                            prefsUtil.setValue(PrefsUtil.KEY_LATEST_ANNOUNCEMENT_SEEN, true)
                            view.onShowAnnouncement()
                        } else {
                            view.onHideAnnouncement()
                        }
                    } else {
                        view.onHideAnnouncement()
                    }
                }, { it.printStackTrace() })
    }

    fun disableAnnouncement() {
        prefsUtil.setValue(PrefsUtil.KEY_LATEST_ANNOUNCEMENT_DISMISSED, true)
    }

    private fun getAllDisplayableAccounts(): MutableList<ItemAccount> {
        val mutableList = mutableListOf<ItemAccount>()

        val legacyAddresses = payloadDataManager.legacyAddresses
                .filter { it.tag != LegacyAddress.ARCHIVED_ADDRESS }

        val accounts = payloadDataManager.accounts
                .filter { !it.isArchived }
                .map {
                    val bigIntBalance = payloadDataManager.getAddressBalance(it.xpub)
                    ItemAccount().apply {
                        label = it.label
                        displayBalance = getBalanceString(accessState.isBtc, bigIntBalance.toLong())
                        absoluteBalance = bigIntBalance.toLong()
                        accountObject = it
                    }
                }

        // Show "All Accounts" if necessary
        if (accounts.size > 1 || !legacyAddresses.isEmpty()) {
            val all = ConsolidatedAccount().apply {
                label = stringUtils.getString(R.string.all_accounts)
                type = ConsolidatedAccount.Type.ALL_ACCOUNTS
            }

            val bigIntBalance = payloadDataManager.walletBalance

            mutableList.add(ItemAccount().apply {
                label = all.label
                displayBalance = getBalanceString(accessState.isBtc, bigIntBalance.toLong())
                absoluteBalance = bigIntBalance.toLong()
                accountObject = all
            })
        }

        mutableList.addAll(accounts)

        // Show "Imported Addresses" if necessary
        if (!legacyAddresses.isEmpty()) {
            val importedAddresses = ConsolidatedAccount().apply {
                label = stringUtils.getString(R.string.imported_addresses)
                type = ConsolidatedAccount.Type.ALL_IMPORTED_ADDRESSES
            }

            val bigIntBalance = payloadDataManager.importedAddressesBalance

            mutableList.add(ItemAccount().apply {
                label = importedAddresses.label
                displayBalance = getBalanceString(accessState.isBtc, bigIntBalance.toLong())
                absoluteBalance = bigIntBalance.toLong()
                accountObject = importedAddresses
            })
        }

        return mutableList
    }

    private fun showSendAddressDialog(fctxId: String) {
        val accountNames = payloadDataManager.accounts
                .filterNot { it.isArchived }
                .mapTo(ArrayList<String>()) { it.label }

        if (accountNames.size == 1) {
            // Only one account, ask if you want to send an address
            view.showSendAddressDialog(fctxId)
        } else {
            // Show dialog allowing user to select which account they want to use
            view.showAccountChoiceDialog(accountNames, fctxId)
        }
    }

    private fun getTransactionsListObservable(itemAccount: ItemAccount) =
            transactionListDataManager.fetchTransactions(itemAccount.accountObject, 50, 0)
                    .doAfterTerminate(this::storeSwipeReceiveAddresses)
                    .doOnNext {
                        displayList.removeAll { it is TransactionSummary }
                        displayList.addAll(it)

                        when {
                            it.isEmpty() -> view.setUiState(UiState.EMPTY)
                            else -> view.setUiState(UiState.CONTENT)
                        }
                        view.onTransactionsUpdated(displayList)
                    }

    private fun getBalanceObservable(itemAccount: ItemAccount) =
            payloadDataManager.updateAllBalances()
                    .doOnComplete {
                        val btcBalance = transactionListDataManager.getBtcBalance(itemAccount.accountObject)
                        val balanceTotal = getBalanceString(accessState.isBtc, btcBalance)
                        view.onTotalBalanceUpdated(balanceTotal)
                    }.toObservable<Nothing>()

    private fun getUpdateTickerObservable() =
            exchangeRateFactory.updateTicker()
                    .doOnComplete {
                        view.onAccountsUpdated(
                                activeAccountAndAddressList,
                                getLastPrice(getFiatCurrency()),
                                getFiatCurrency(),
                                monetaryUtil,
                                accessState.isBtc
                        )
                        view.onExchangeRateUpdated(
                                exchangeRateFactory.getLastPrice(getFiatCurrency()),
                                accessState.isBtc
                        )
                        checkOnboardingStatus()
                    }.toObservable<Nothing>()

    private fun getFacilitatedTransactionsObservable(): Observable<MutableList<ContactTransactionModel>> {
        if (view.getIfContactsEnabled()) {
            return contactsDataManager.fetchContacts()
                    .andThen<Contact>(contactsDataManager.contactsWithUnreadPaymentRequests)
                    .toList()
                    .flatMapObservable { contactsDataManager.refreshFacilitatedTransactions() }
                    .toList()
                    .onErrorReturnItem(emptyList())
                    .toObservable()
                    .doOnNext {
                        handlePendingTransactions(it)
                        view.onContactsHashMapUpdated(
                                contactsDataManager.contactsTransactionMap,
                                contactsDataManager.notesTransactionMap
                        )
                    }
        } else {
            return Observable.empty<MutableList<ContactTransactionModel>>()
        }
    }

    private fun refreshFacilitatedTransactions() {
        getFacilitatedTransactionsObservable()
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe(
                        { /* No-op */ },
                        { throwable -> throwable.printStackTrace() })
    }

    private fun storeSwipeReceiveAddresses() {
        // Defer to background thread as deriving addresses is quite processor intensive
        Completable.fromCallable {
            swipeToReceiveHelper.updateAndStoreAddresses()
            Void.TYPE
        }.subscribeOn(Schedulers.computation())
                .compose(RxUtil.addCompletableToCompositeDisposable(this))
                .subscribe(
                        { /* No-op */ },
                        { it.printStackTrace() })
    }

    private fun subscribeToEvents() {
        contactsEventObservable = rxBus.register(ContactsEvent::class.java)
        contactsEventObservable?.subscribe({ refreshFacilitatedTransactions() })

        authEventObservable = rxBus.register(AuthEvent::class.java)
        authEventObservable?.subscribe({
            displayList.clear()
            transactionListDataManager.clearTransactionList()
            contactsDataManager.resetContacts()
        })

        notificationObservable = rxBus.register(NotificationPayload::class.java)
        notificationObservable?.subscribe({ notificationPayload ->
            if (notificationPayload?.type == NotificationPayload.NotificationType.PAYMENT) {
                refreshFacilitatedTransactions()
            }
        })
    }

    private fun handlePendingTransactions(transactions: List<ContactTransactionModel>) {
        displayList.removeAll { it !is TransactionSummary }
        view.showFctxRequiringAttention(getNumberOfFctxRequiringAttention(transactions))
        if (transactions.isNotEmpty()) {
            val reversed = transactions.sortedWith(ContactTransactionDateComparator()).reversed()
            displayList.add(0, stringUtils.getString(R.string.contacts_pending_transaction))
            displayList.addAll(1, reversed)
            displayList.add(reversed.size + 1, stringUtils.getString(R.string.contacts_transaction_history))
            view.onTransactionsUpdated(displayList)
        } else {
            view.onTransactionsUpdated(displayList)
        }
    }

    private fun getNumberOfFctxRequiringAttention(facilitatedTransactions: List<ContactTransactionModel>): Int {
        var value = 0
        facilitatedTransactions
                .asSequence()
                .map { it.facilitatedTransaction }
                .forEach {
                    if (it.state == FacilitatedTransaction.STATE_WAITING_FOR_ADDRESS
                            && it.role == FacilitatedTransaction.ROLE_RPR_RECEIVER) {
                        value++
                    } else if (it.state == FacilitatedTransaction.STATE_WAITING_FOR_PAYMENT
                            && it.role == FacilitatedTransaction.ROLE_RPR_RECEIVER) {
                        value++
                    }
                }
        return value
    }

    private fun checkOnboardingStatus() {
        buyDataManager.canBuy
                .compose(RxUtil.addObservableToCompositeDisposable(this))
                .subscribe({
                    val onboardingPages = getOnboardingPages(it ?: false)
                    view.onLoadOnboardingPages(onboardingPages)
                }, { it.printStackTrace() })
    }

    private fun getOnboardingPages(isBuyAllowed: Boolean): List<OnboardingPagerContent> {
        val pages = mutableListOf<OnboardingPagerContent>()
        if (isBuyAllowed) {
            // Buy bitcoin prompt
            pages.add(
                    OnboardingPagerContent(
                            stringUtils.getString(R.string.onboarding_current_price),
                            getFormattedPriceString(),
                            stringUtils.getString(R.string.onboarding_buy_content),
                            stringUtils.getString(R.string.onboarding_buy_bitcoin),
                            MainActivity.ACTION_BUY,
                            R.color.primary_blue_accent,
                            R.drawable.vector_buy_offset
                    ))
        }

        // Receive bitcoin
        pages.add(
                OnboardingPagerContent(
                        stringUtils.getString(R.string.onboarding_receive_bitcoin),
                        "",
                        stringUtils.getString(R.string.onboarding_receive_content),
                        stringUtils.getString(R.string.receive_bitcoin),
                        MainActivity.ACTION_RECEIVE,
                        R.color.secondary_teal_medium,
                        R.drawable.vector_receive_offset
                ))

        // QR Codes
        pages.add(
                OnboardingPagerContent(
                        stringUtils.getString(R.string.onboarding_qr_codes),
                        "",
                        stringUtils.getString(R.string.onboarding_qr_codes_content),
                        stringUtils.getString(R.string.onboarding_scan_address),
                        MainActivity.ACTION_SEND,
                        R.color.primary_navy_medium,
                        R.drawable.vector_qr_offset
                ))
        return pages
    }

    private fun getFormattedPriceString(): String {
        val lastPrice = getLastPrice(getFiatCurrency())
        val fiatSymbol = exchangeRateFactory.getSymbol(getFiatCurrency())
        val format = DecimalFormat().apply { minimumFractionDigits = 2 }

        return stringUtils.getFormattedString(
                R.string.current_price_btc,
                "$fiatSymbol${format.format(lastPrice)}"
        )
    }

    private fun getBalanceString(isBTC: Boolean, btcBalance: Long): String {
        val strFiat = prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY)
        val fiatBalance = exchangeRateFactory.getLastPrice(strFiat) * (btcBalance / 1e8)

        return if (isBTC) {
            "${monetaryUtil.getDisplayAmountWithFormatting(btcBalance)} ${getDisplayUnits()}"
        } else {
            "${monetaryUtil.getFiatFormat(strFiat).format(fiatBalance)} $strFiat"
        }
    }

    private fun getLastPrice(fiat: String): Double = exchangeRateFactory.getLastPrice(fiat)

    private fun getDisplayUnits(): String =
            monetaryUtil.btcUnits[prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC)].toString()

    private fun getFiatCurrency(): String =
            prefsUtil.getValue(PrefsUtil.KEY_SELECTED_FIAT, PrefsUtil.DEFAULT_CURRENCY)

    private val monetaryUtil: MonetaryUtil by lazy(LazyThreadSafetyMode.NONE) {
        MonetaryUtil(prefsUtil.getValue(PrefsUtil.KEY_BTC_UNITS, MonetaryUtil.UNIT_BTC))
    }

}
