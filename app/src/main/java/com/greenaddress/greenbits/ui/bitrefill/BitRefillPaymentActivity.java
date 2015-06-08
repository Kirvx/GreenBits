package com.greenaddress.greenbits.ui.bitrefill;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.dd.CircularProgressButton;
import com.greenaddress.greenbits.ui.ActionBarActivity;
import com.greenaddress.greenbits.ui.R;
import com.greenaddress.greenbits.ui.TabbedMainActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class BitRefillPaymentActivity extends ActionBarActivity {

  private static final String TAG = "BitrefillPkgPay";
  private static final String URL = String.format("%s/order/",
      BitRefillActivity.BASE_URL);

  private String post_order(final JSONObject order) throws IOException {
    Log.i(TAG, URL);

    Log.i(TAG, order.toString());
    final URL payUrl = new URL(URL);
    final HttpURLConnection urlC = (HttpURLConnection) payUrl.openConnection();

    try {

      urlC.setRequestProperty("Authorization", BitRefillActivity.BASIC_AUTH);
      urlC.setDoOutput(true);
      urlC.setRequestProperty("Content-Type", "application/json");
      urlC.setRequestProperty("Accept", "application/json");
      urlC.setRequestMethod("POST");
      urlC.connect();

      final OutputStream os = urlC.getOutputStream();
      final OutputStreamWriter osw = new OutputStreamWriter(os, "UTF-8");

      osw.write(order.toString());
      osw.flush();
      osw.close();

      final InputStream in = new BufferedInputStream(urlC.getInputStream());

      if (!payUrl.getHost().equals(urlC.getURL().getHost())) {
        startActivity(new Intent(Intent.ACTION_VIEW,
            Uri.parse(urlC.getURL().getHost())));
        return null;
      }

      final StringBuilder sb = new StringBuilder();
      final BufferedReader rd = new BufferedReader(new InputStreamReader(in));

      String line;

      while ((line = rd.readLine()) != null) {
        sb.append(line);
      }

      rd.close();

      return sb.toString();

    } finally {
      urlC.disconnect();
    }
  }

  @Override
  protected void onCreate(final Bundle savedInstanceState) {

    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_bit_refill_pay);

    final Intent intent = getIntent();
    final String mobileNumber = intent.getExtras().getString("number");
    final String valuePackage = intent.getExtras().getString("valuePackage");
    final String operatorSlug = intent.getExtras().getString("operatorSlug");
    final String satoshiPrice = intent.getExtras().getString("satoshiPrice");

    final String operatorName = intent.getExtras().getString("operatorName");
    final String currency = intent.getExtras().getString("currency");

    final CircularProgressButton payButton = (CircularProgressButton)
        findViewById(R.id.payButton);

    final EditText emailText = (EditText) findViewById(R.id.emailText);
    final TextView operator = (TextView) findViewById(R.id
        .operatorPackageValue);
    operator.setText(String.format("%s, %s %s", operatorName, valuePackage,
        currency));

    final TextView satoshiPriceText = (TextView) findViewById(R.id
        .satoshiPriceValue);
    satoshiPriceText.setText(satoshiPrice);

    final TextView mobileNumberText = (TextView) findViewById(R.id
        .mobileNumberValue);
    mobileNumberText.setText(mobileNumber);

    final View.OnClickListener ocl = new View.OnClickListener() {

      @Override
      public void onClick(final View v) {

        new AsyncTask<Void, Void, String>() {
          @Override
          protected String doInBackground(final Void... Voids) {
            try {
              final JSONObject orderReq = new JSONObject();
              orderReq.put("operatorSlug", operatorSlug);
              orderReq.put("valuePackage", valuePackage);
              orderReq.put("number", mobileNumber);
              orderReq.put("email", emailText.getText().toString());
              final String json = post_order(orderReq);
              Log.i(TAG, json);
              return json;

            } catch (final IOException e) {
              e.printStackTrace();

            } catch (final JSONException e) {
              e.printStackTrace();
            }
            return null;

          }

          protected void onPostExecute(final String result) {
            payButton.setIndeterminateProgressMode(false);
            payButton.setProgress(0);

            try {
              final JSONObject payment = new JSONObject(result)
                  .getJSONObject("payment");

              startActivity(new Intent(Intent.ACTION_VIEW,
                  Uri.parse(String.format("%s&r=%s", payment.getString
                      ("BIP21"), URLEncoder.encode(payment.getString
                      ("BIP73"), "utf-8"))), BitRefillPaymentActivity.this,
                  TabbedMainActivity.class));
              finish();

            } catch (final JSONException e) {
              e.printStackTrace();
            } catch (final UnsupportedEncodingException e) {
              e.printStackTrace();
            }
          }

        }.execute();

        payButton.setIndeterminateProgressMode(true);
        payButton.setProgress(50);
      }
    };

    payButton.setOnClickListener(ocl);
  }
}