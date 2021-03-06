package com.greenaddress.greenbits.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.zxing.WriterException;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.QrBitmap;

import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TwoFactorActivity extends ActionBarActivity {

    private String twoFacType, twoFacTypeName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final GaService gaService = getGAService();
        if (gaService == null) {
            finish();
            return;
        }
        final Map<?, ?> twoFacConfig = gaService.getTwoFacConfig();
        twoFacType = getIntent().getStringExtra("method");
        final String[] allTwoFac = getResources().getStringArray(R.array.twoFactorChoices);
        final String[] allTwoFacSystem = getResources().getStringArray(R.array.twoFactorChoicesSystem);
        final List<String> enabledTwoFacNames = gaService.getEnabledTwoFacNames(false);
        final List<String> enabledTwoFacNamesSystem = gaService.getEnabledTwoFacNames(true);
        for (int i = 0; i < allTwoFacSystem.length; ++i) {
            if (allTwoFacSystem[i].equals(twoFacType)) {
                twoFacTypeName = allTwoFac[i];
                break;
            }
        }
        setTitle(new Formatter().format(getTitle().toString(), twoFacTypeName).toString());

        if (twoFacConfig == null) {
            finish();
        }
        if (enabledTwoFacNames.size() > 1) {
            setContentView(R.layout.activity_two_factor_1_choose);
            final Button continueButton = (Button) findViewById(R.id.continueButton);
            final RadioGroup radioGroup = (RadioGroup) findViewById(R.id.radioGroup);
            radioGroup.removeViews(0, radioGroup.getChildCount());

            for (int i = 0; i < enabledTwoFacNames.size(); ++i) {
                RadioButton button = new RadioButton(TwoFactorActivity.this);
                button.setText(enabledTwoFacNames.get(i));
                button.setId(i);
                radioGroup.addView(button);
            }

            radioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(final RadioGroup group, final int checkedId) {
                    continueButton.setEnabled(true);
                }
            });

            continueButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String methodName = enabledTwoFacNamesSystem.get(radioGroup.getCheckedRadioButtonId());
                    final int stepsCount;
                    if (twoFacType.equals("gauth")) {
                        // details and confirmation code together
                        stepsCount = 3;
                    } else {
                        stepsCount = 4;
                    }
                    if (!methodName.equals("gauth")) {
                        final Map<String, String> data = new HashMap<>();
                        data.put("method", twoFacType);
                        gaService.requestTwoFacCode(methodName, "enable_2fa", data);
                    }
                    showProvideAuthCode(2, stepsCount, enabledTwoFacNames.get(radioGroup.getCheckedRadioButtonId()),
                            methodName, twoFacType);
                }
            });
        } else if (enabledTwoFacNames.size() == 1) {
            // just one 2FA enabled - go straight to code verification
            final String methodName = enabledTwoFacNamesSystem.get(0);
            final int stepsCount;
            if (twoFacType.equals("gauth")) {
                // details and confirmation code together
                stepsCount = 2;
            } else {
                stepsCount = 3;
            }
            if (!methodName.equals("gauth")) {
                final Map<String, String> data = new HashMap<>();
                data.put("method", twoFacType);
                gaService.requestTwoFacCode(methodName, "enable_2fa", data);
            }
            showProvideAuthCode(1, stepsCount, enabledTwoFacNames.get(0),
                    methodName, twoFacType);
        } else {
            // no 2FA enabled - go straight to 2FA details
            if (twoFacType.equals("gauth")) {
                showGauthDetails(1, 1, null);
            } else {
                showProvideDetails(1, 2, null);
            }
        }
    }

    private void showProvideDetails(final int stepNum, final int numSteps, @Nullable final String proxyCode) {
        final GaService gaService = getGAService();
        setContentView(R.layout.activity_two_factor_3_provide_details);
        final Button continueButton = (Button) findViewById(R.id.continueButton);
        final TextView prompt = (TextView) findViewById(R.id.prompt);
        final TextView details = (TextView) findViewById(R.id.details);
        prompt.setText(new Formatter().format(
                prompt.getText().toString(),
                twoFacType.equals("email") ?
                        getResources().getString(R.string.emailAddress) :
                        getResources().getString(R.string.phoneNumber)).toString());
        if (!twoFacType.equals("email")) {
            findViewById(R.id.emailNotices).setVisibility(View.GONE);
        }
        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar);
        progressBar.setProgress(stepNum);
        progressBar.setMax(numSteps);

        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (details.getText().toString().trim().isEmpty()) return;
                continueButton.setEnabled(false);
                final Map<String, String> twoFacData = new HashMap<>();
                if (proxyCode != null) {  // two factor required
                    twoFacData.put("method", "proxy");
                    twoFacData.put("code", proxyCode);
                }
                Futures.addCallback(gaService.initEnableTwoFac(twoFacType, details.getText().toString(), twoFacData), new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(final @Nullable Boolean result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showProvideConfirmationCode(stepNum + 1, numSteps);
                            }
                        });
                    }

                    @Override
                    public void onFailure(final @NonNull Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                continueButton.setEnabled(true);
                                Toast.makeText(TwoFactorActivity.this, t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                        t.printStackTrace();
                    }
                });
            }
        });
    }

    private void showProvideAuthCode(final int stepNum, final int numSteps, final String oldMethodName, final String oldMethod, @NonNull final String newMethod) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(R.layout.activity_two_factor_2_4_provide_code, null, false);
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
        setContentView(view);

        final TextView description = (TextView) findViewById(R.id.description);
        final TextView prompt = (TextView) findViewById(R.id.prompt);
        final EditText code = (EditText) findViewById(R.id.code);
        description.setText(getResources().getString(R.string.twoFacProvideAuthCodeDescription));
        prompt.setText(new Formatter().format(prompt.getText().toString(), oldMethodName).toString());
        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar);
        progressBar.setProgress(stepNum);
        progressBar.setMax(numSteps);
        final GaService gaService = getGAService();


        final Button continueButton = (Button) findViewById(R.id.continueButton);
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                continueButton.setEnabled(false);
                final Map<String, String> data = new HashMap<>();
                data.put("method", oldMethod);
                data.put("code", code.getText().toString());
                Futures.addCallback(gaService.requestTwoFacCode("proxy", newMethod, data), new FutureCallback<Object>() {
                    @Override
                    public void onSuccess(@Nullable final Object proxyCode) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (newMethod.equals("gauth")) {
                                    showGauthDetails(stepNum + 1, numSteps, (String) proxyCode);
                                } else {
                                    showProvideDetails(stepNum + 1, numSteps, (String) proxyCode);
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(final @NonNull Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                continueButton.setEnabled(true);
                                Toast.makeText(TwoFactorActivity.this, t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                        t.printStackTrace();
                    }
                });
            }
        });
    }

    private void showGauthDetails(final int stepNum, final int numSteps, @Nullable final String proxyCode) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(R.layout.activity_two_factor_3_gauth_details, null, false);
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
        setContentView(view);

        final ImageView imageView = (ImageView) findViewById(R.id.imageView);
        final TextView textCode = (TextView) findViewById(R.id.textCode);
        final Button continueButton = (Button) findViewById(R.id.continueButton);
        final EditText code = (EditText) findViewById(R.id.code);
        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar);
        progressBar.setProgress(stepNum);
        progressBar.setMax(numSteps);
        final GaService gaService = getGAService();

        final String gauth_url = (String) gaService.getTwoFacConfig().get("gauth_url");
        try {
            final BitmapDrawable bd = new BitmapDrawable(getResources(), new QrBitmap(gauth_url, 0).call().qrcode);
            bd.setFilterBitmap(false);
            imageView.setImageDrawable(bd);
        } catch (WriterException e) {
            e.printStackTrace();
        }
        final String gauthCode = gauth_url.split("=")[1];
        textCode.setText(new Formatter().format(
                getResources().getString(R.string.twoFacGauthTextCode),
                gauthCode).toString());

        textCode.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        final ClipboardManager clipboard = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        final ClipData clip = ClipData.newPlainText("data", gauthCode);
                        clipboard.setPrimaryClip(clip);

                        Toast.makeText(TwoFactorActivity.this, getString(R.string.warnOnPaste), Toast.LENGTH_LONG).show();
                    }
                }
        );

        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Map<String, String> twoFacData = new HashMap<>();
                if (proxyCode != null) {
                    twoFacData.put("method", "proxy");
                    twoFacData.put("code", proxyCode);
                }
                continueButton.setEnabled(false);
                Futures.addCallback(gaService.enableTwoFac(code.getText().toString().trim(), twoFacData), new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(final @Nullable Boolean result) {
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override
                    public void onFailure(final @NonNull Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                continueButton.setEnabled(true);
                                Toast.makeText(TwoFactorActivity.this, t.getMessage(), Toast.LENGTH_LONG).show();
                                t.printStackTrace();
                            }
                        });
                    }
                });
            }
        });
    }

    private void showProvideConfirmationCode(final int stepNum, final int numSteps) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(R.layout.activity_two_factor_2_4_provide_code, null, false);
        view.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_right));
        setContentView(view);

        final Button continueButton = (Button) findViewById(R.id.continueButton);
        final EditText code = (EditText) findViewById(R.id.code);
        final TextView prompt = (TextView) findViewById(R.id.prompt);
        prompt.setText(new Formatter().format(prompt.getText().toString(), twoFacTypeName).toString());
        final GaService gaService = getGAService();
        final ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar);
        progressBar.setProgress(stepNum);
        progressBar.setMax(numSteps);

        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (6 != code.getText().toString().trim().length()) return;
                continueButton.setEnabled(false);
                Futures.addCallback(gaService.enableTwoFac(twoFacType, code.getText().toString().trim()), new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(@Nullable Boolean result) {
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override
                    public void onFailure(final @NonNull Throwable t) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                continueButton.setEnabled(true);
                                Toast.makeText(TwoFactorActivity.this, t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                        t.printStackTrace();
                    }
                });
            }
        });
    }

}
