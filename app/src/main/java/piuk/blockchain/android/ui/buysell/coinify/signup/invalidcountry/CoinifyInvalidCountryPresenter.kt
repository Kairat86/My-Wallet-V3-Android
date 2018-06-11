package piuk.blockchain.android.ui.buysell.coinify.signup.invalidcountry

import piuk.blockchain.androidcoreui.ui.base.BasePresenter
import javax.inject.Inject

class CoinifyInvalidCountryPresenter @Inject constructor(

) : BasePresenter<CoinifyInvalidCountryView>() {

    override fun onViewReady() {
        // No-op
    }

    fun requestEmailOnBuySellAvailability() {
        //TODO The current google doc is not accepting further responses - Due to time constraint we'll come back to this later
        view.close()
    }

}