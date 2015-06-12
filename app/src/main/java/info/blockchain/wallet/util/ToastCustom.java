package info.blockchain.wallet.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import info.blockchain.wallet.R;

/**
 * Created by riaanvos on 12/06/15.
 */
public class ToastCustom{

    public static final String TYPE_ERROR = "TYPE_ERROR";
    public static final String TYPE_GENERAL = "TYPE_GENERAL";
    public static final String TYPE_OK = "TYPE_OK";

    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private static Toast toast = null;
    //ToastCustom.makeText(getActivity(), "This is some info", ToastCustom.LENGTH_LONG, ToastCustom.TYPE_OK);

    public static void makeText(Context context, CharSequence text, int duration, String type) {

        toast = Toast.makeText(context, text, duration);

        LayoutInflater inflate = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View v = inflate.inflate(R.layout.transient_notification, null);
        TextView tv = (TextView) v.findViewById(R.id.message);
        tv.setText(text);

        if (type.equals(TYPE_ERROR)){
            tv.setBackground(context.getResources().getDrawable(R.drawable.rounded_view_toast_error));
            tv.setTextColor(context.getResources().getColor(R.color.toast_error_text));

        }else if(type.equals(TYPE_GENERAL)){
            tv.setBackground(context.getResources().getDrawable(R.drawable.rounded_view_toast_warning));
            tv.setTextColor(context.getResources().getColor(R.color.toast_warning_text));

        }else if(type.equals(TYPE_OK)){
            tv.setBackground(context.getResources().getDrawable(R.drawable.rounded_view_toast_info));
            tv.setTextColor(context.getResources().getColor(R.color.toast_info_text));
        }
        toast.setView(v);
        toast.show();
    }
}
