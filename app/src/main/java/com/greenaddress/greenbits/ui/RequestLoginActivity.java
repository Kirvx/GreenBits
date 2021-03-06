package com.greenaddress.greenbits.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.Theme;
import com.btchip.BTChipDongle.BTChipPublicKey;
import com.btchip.comm.BTChipTransport;
import com.btchip.comm.android.BTChipTransportAndroid;
import com.btchip.comm.android.BTChipTransportAndroidNFC;
import com.btchip.utils.KeyUtils;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.greenaddress.greenapi.LoginData;
import com.greenaddress.greenapi.LoginFailed;
import com.greenaddress.greenbits.GaService;
import com.greenaddress.greenbits.GreenAddressApplication;
import com.greenaddress.greenbits.wallets.BTChipHWWallet;
import com.greenaddress.greenbits.wallets.TrezorHWWallet;
import com.satoshilabs.trezor.Trezor;
import com.satoshilabs.trezor.TrezorGUICallback;

import java.util.Formatter;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.ExecutionException;

import nordpol.android.AndroidCard;
import nordpol.android.OnDiscoveredTagListener;
import nordpol.android.TagDispatcher;

public class RequestLoginActivity extends Activity implements Observer, OnDiscoveredTagListener {

    @NonNull private static final String TAG = RequestLoginActivity.class.getSimpleName();
    @NonNull private static final byte DUMMY_COMMAND[] = { (byte)0xE0, (byte)0xC4, (byte)0x00, (byte)0x00, (byte)0x00 };

    @Nullable
    private Dialog btchipDialog = null;
    @Nullable
    private BTChipHWWallet hwWallet = null;
    private TagDispatcher tagDispatcher;
    private Tag tag;
    private SettableFuture<BTChipTransport> transportFuture;
    private MaterialDialog nfcWaitDialog;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_first_login_requested);

        tag = getIntent().getParcelableExtra(NfcAdapter.EXTRA_TAG);

        tagDispatcher = TagDispatcher.get(this, this);

        getGAApp().getConnectionObservable().addObserver(this);        

        if (((tag != null) && (NfcAdapter.ACTION_TECH_DISCOVERED.equals(getIntent().getAction()))) ||
                (getIntent().getAction() != null &&
                        getIntent().getAction().equals("android.hardware.usb.action.USB_DEVICE_ATTACHED"))) {
            final Trezor t;
            if (tag != null) {
                t = null;
            } else {
                t = Trezor.getDevice(this, new TrezorGUICallback() {
                    @Override
                    public String pinMatrixRequest() {
                        final SettableFuture<String> ret = SettableFuture.create();
                        RequestLoginActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                final View inflatedLayout = getLayoutInflater().inflate(R.layout.dialog_trezor_pin, null, false);
                                final Button[] buttons = new Button[]{
                                        // upside down
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton7),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton8),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton9),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton4),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton5),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton6),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton1),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton2),
                                        (Button) inflatedLayout.findViewById(R.id.trezorPinButton3)
                                };
                                final EditText pinValue = (EditText) inflatedLayout.findViewById(R.id.trezorPinValue);
                                for (int i = 0; i < 9; ++i) {
                                    final int ii = i;
                                    buttons[i].setOnClickListener(new View.OnClickListener() {
                                        @Override
                                        public void onClick(View v) {
                                            pinValue.setText(pinValue.getText().toString() + (ii + 1));
                                            pinValue.setSelection(pinValue.getText().toString().length());
                                        }
                                    });
                                }
                                new MaterialDialog.Builder(RequestLoginActivity.this)
                                        .title("Hardware Wallet PIN")
                                        .customView(inflatedLayout, true)
                                        .positiveText("OK")
                                        .negativeText("CANCEL")
                                        .positiveColorRes(R.color.accent)
                                        .negativeColorRes(R.color.accent)
                                        .titleColorRes(R.color.white)
                                        .contentColorRes(android.R.color.white)
                                        .theme(Theme.DARK)
                                        .onPositive(new MaterialDialog.SingleButtonCallback() {
                                            @Override
                                            public void onClick(final @NonNull MaterialDialog dialog, final @NonNull DialogAction which) {
                                                ret.set(pinValue.getText().toString());
                                            }
                                        })
                                        .build().show();
                            }
                        });
                        try {
                            return ret.get();
                        } catch (@NonNull final InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                            return "";
                        }
                    }

                    @Override
                    public String passphraseRequest() {
                        final SettableFuture<String> ret = SettableFuture.create();
                        RequestLoginActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                final View inflatedLayout = getLayoutInflater().inflate(R.layout.dialog_trezor_passphrase, null, false);
                                final EditText passphraseValue = (EditText) inflatedLayout.findViewById(R.id.trezorPassphraseValue);
                                new MaterialDialog.Builder(RequestLoginActivity.this)
                                        .title("Hardware Wallet passphrase")
                                        .customView(inflatedLayout, true)
                                        .positiveText("OK")
                                        .negativeText("CANCEL")
                                        .positiveColorRes(R.color.accent)
                                        .negativeColorRes(R.color.accent)
                                        .titleColorRes(R.color.white)
                                        .contentColorRes(android.R.color.white)
                                        .theme(Theme.DARK)
                                        .onPositive(new MaterialDialog.SingleButtonCallback() {
                                            @Override
                                            public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                                                ret.set(passphraseValue.getText().toString());
                                            }
                                        })
                                        .build().show();
                            }
                        });
                        try {
                            return ret.get();
                        } catch (@NonNull InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                            return "";
                        }
                    }
                });
            }
            if (t != null) {
                final List<Integer> version = t.getFirmwareVersion();
                if (t.getVendorId() == 21324 && (version.get(0) < 1 ||
                        (version.get(0) == 1) && (version.get(1) < 3))) {
                    final TextView instructions = (TextView) findViewById(R.id.firstLoginRequestedInstructionsText);
                    instructions.setText(getResources().getString(R.string.firstLoginRequestedInstructionsOldTrezor));
                    return;
                }
                if (t.getVendorId() == 11044 && (version.get(0) < 1)) {
                    final TextView instructions = (TextView) findViewById(R.id.firstLoginRequestedInstructionsText);
                    instructions.setText(getResources().getString(R.string.firstLoginRequestedInstructionsOldTrezor));
                    return;
                }
                Futures.addCallback(getGAApp().onServiceAttached, new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        final GaService gaService = getGAService();

                        Futures.addCallback(Futures.transform(gaService.onConnected, new AsyncFunction<Void, LoginData>() {
                            @NonNull
                            @Override
                            public ListenableFuture<LoginData> apply(@Nullable final Void input) throws Exception {
                                return gaService.login(new TrezorHWWallet(t));
                            }
                        }), new FutureCallback<LoginData>() {
                            @Override
                            public void onSuccess(@Nullable final LoginData result) {
                                final Intent main = new Intent(RequestLoginActivity.this, TabbedMainActivity.class);
                                startActivity(main);
                                RequestLoginActivity.this.finish();
                            }

                            @Override
                            public void onFailure(@NonNull final Throwable t) {
                                if (t instanceof LoginFailed) {
                                    // login failed - most likely TREZOR/KeepKey/BWALLET/AvalonWallet not paired
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            new MaterialDialog.Builder(RequestLoginActivity.this)
                                                    .title(getResources().getString(R.string.trezor_login_failed))
                                                    .content(getResources().getString(R.string.trezor_login_failed_details))
                                                    .build().show();
                                        }
                                    });
                                } else {
                                    RequestLoginActivity.this.finish();
                                }
                            }
                        });
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {
                        t.printStackTrace();
                    }
                });
            } else {
                final TextView edit = (TextView) findViewById(R.id.firstLoginRequestedInstructionsText);
                edit.setVisibility(View.GONE);
                // not TREZOR/KeepKey/BWALLET/AvalonWallet, so must be BTChip
                if (tag != null) {
                    showPinDialog();
                } else {
                    final UsbDevice device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        showPinDialog(device);
                    }
                }
            }
            return;
        }

        if (getSharedPreferences("pin", MODE_PRIVATE).getString("ident", null) != null) {
            final Intent pin = new Intent(this, PinActivity.class);
            startActivityForResult(pin, 0);
        } else {
            final Intent mnemonic = new Intent(this, MnemonicActivity.class);
            startActivityForResult(mnemonic, 0);
        }
    }

    private void showPinDialog() {
        showPinDialog(null);
    }

    private void showPinDialog(@Nullable final UsbDevice device) {
        final SettableFuture<String> pinFuture = SettableFuture.create();
        RequestLoginActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final View inflatedLayout = getLayoutInflater().inflate(R.layout.dialog_btchip_pin, null, false);
                final EditText pinValue = (EditText) inflatedLayout.findViewById(R.id.btchipPINValue);
                final MaterialDialog.Builder builder = new MaterialDialog.Builder(RequestLoginActivity.this)
                        .title("BTChip PIN")
                        .customView(inflatedLayout, true)
                        .positiveColorRes(R.color.accent)
                        .negativeColorRes(R.color.accent)
                        .titleColorRes(R.color.white)
                        .contentColorRes(android.R.color.white)
                        .theme(Theme.DARK)
                        .positiveText("OK")
                        .negativeText("CANCEL")
                        .onPositive(new MaterialDialog.SingleButtonCallback() {
                            @Override
                            public void onClick(final @NonNull MaterialDialog dialog, final @NonNull DialogAction which) {
                                final ProgressBar prog = (ProgressBar) findViewById(R.id.signingLogin);
                                prog.setVisibility(View.VISIBLE);
                                pinFuture.set(pinValue.getText().toString());
                            }
                        })
                        .onNegative(new MaterialDialog.SingleButtonCallback() {
                            @Override
                            public void onClick(final @NonNull MaterialDialog dialog, final @NonNull DialogAction which) {
                                Toast.makeText(RequestLoginActivity.this, "No PIN provided, exiting.", Toast.LENGTH_LONG).show();
                                RequestLoginActivity.this.finish();                            }
                        });

                btchipDialog = builder.build();

                // (FIXME not sure if there's any smaller subset of these 3 calls below which works too)
                pinValue.requestFocus();
                final Window btchipWindow = btchipDialog.getWindow();
                btchipWindow.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                btchipWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                pinValue.setOnEditorActionListener(
                        new EditText.OnEditorActionListener() {
                            @Override
                            public boolean onEditorAction(final TextView v, final int actionId, @Nullable final KeyEvent event) {
                                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                                        actionId == EditorInfo.IME_ACTION_DONE ||
                                        (event != null && event.getAction() == KeyEvent.ACTION_DOWN) &&
                                                event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                                    if (event == null || !event.isShiftPressed()) {
                                        // the user is done typing.
                                        final ProgressBar prog = (ProgressBar) findViewById(R.id.signingLogin);
                                        prog.setVisibility(View.VISIBLE);
                                        btchipDialog.hide();
                                        pinFuture.set(pinValue.getText().toString());
                                        return true; // consume.
                                    }
                                }
                                return false; // pass on to other listeners.
                            }
                        }
                );
                btchipDialog.show();
            }
        });
        Futures.addCallback(getGAApp().onServiceAttached, new FutureCallback<Void>() {
            @Override
            public void onSuccess(final @Nullable Void result) {
                final GaService gaService = getGAService();
                Futures.addCallback(Futures.transform(gaService.onConnected, new AsyncFunction<Void, LoginData>() {
                    @NonNull
                    @Override
                    public ListenableFuture<LoginData> apply(@Nullable final Void input) throws Exception {
                        return Futures.transform(pinFuture, new AsyncFunction<String, LoginData>() {
                            @NonNull
                            @Override
                            public ListenableFuture<LoginData> apply(@NonNull final String pin) throws Exception {

                                transportFuture = SettableFuture.create();
                                if (device != null) {
                                    final UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                                    transportFuture.set(BTChipTransportAndroid.open(manager, device));
                                } else {
                                    // If the tag was already tapped, work with it
                                    final BTChipTransport transport = getTransport(tag);
                                    if (transport != null) {
                                        transportFuture.set(transport);
                                    } else {
                                        // Prompt the user to tap
                                        nfcWaitDialog = new MaterialDialog.Builder(RequestLoginActivity.this)
                                                .title("BTChip")
                                                .content("Please tap card")
                                                .build();
                                        nfcWaitDialog.show();
                                    }
                                }
                                return Futures.transform(transportFuture, new AsyncFunction<BTChipTransport, LoginData>() {
                                    @Nullable
                                    @Override
                                    public ListenableFuture<LoginData> apply(final @Nullable BTChipTransport transport) {
                                        final SettableFuture<Integer> remainingAttemptsFuture = SettableFuture.create();
                                        hwWallet = new BTChipHWWallet(transport, RequestLoginActivity.this, pin, remainingAttemptsFuture);
                                        return Futures.transform(remainingAttemptsFuture, new AsyncFunction<Integer, LoginData>() {
                                            @Nullable
                                            @Override
                                            public ListenableFuture<LoginData> apply(final @Nullable Integer input) {
                                                final int remainingAttempts = input;

                                                if (remainingAttempts == -1) {
                                                    // -1 means success
                                                    return gaService.login(hwWallet);
                                                } else {
                                                    RequestLoginActivity.this.runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (remainingAttempts > 0) {
                                                                Toast.makeText(RequestLoginActivity.this, new Formatter().format(
                                                                        getResources().getString(R.string.btchipInvalidPIN), remainingAttempts).toString(), Toast.LENGTH_LONG).show();

                                                            } else {
                                                                Toast.makeText(RequestLoginActivity.this, getResources().getString(R.string.btchipNotSetup), Toast.LENGTH_LONG).show();
                                                            }

                                                            RequestLoginActivity.this.finish();
                                                        }
                                                    });
                                                    return Futures.immediateFuture(null);
                                                }
                                            }
                                        });
                                    }
                                });
                            }
                        });
                    }
                }), new FutureCallback<LoginData>() {
                    @Override
                    public void onSuccess(@Nullable final LoginData result) {
                        if (result != null) {
                            final Intent main = new Intent(RequestLoginActivity.this, TabbedMainActivity.class);
                            startActivity(main);
                            RequestLoginActivity.this.finish();
                        }
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable t) {
                        t.printStackTrace();
                        if (t instanceof LoginFailed) {
                            // Attempt auto register
                            try {
                                final BTChipPublicKey masterPublicKey = hwWallet.getDongle().getWalletPublicKey("");
                                final BTChipPublicKey loginPublicKey = hwWallet.getDongle().getWalletPublicKey("18241'");
                                Futures.addCallback(gaService.signup(hwWallet, KeyUtils.compressPublicKey(masterPublicKey.getPublicKey()), masterPublicKey.getChainCode(), KeyUtils.compressPublicKey(loginPublicKey.getPublicKey()), loginPublicKey.getChainCode()),
                                        new FutureCallback<LoginData>() {

                                            @Override
                                            public void onSuccess(@Nullable final LoginData result) {
                                                final Intent main = new Intent(RequestLoginActivity.this, TabbedMainActivity.class);
                                                startActivity(main);
                                                RequestLoginActivity.this.finish();
                                            }

                                            @Override
                                            public void onFailure(@NonNull final Throwable t) {
                                                t.printStackTrace();
                                                RequestLoginActivity.this.finish();
                                            }
                                        });
                            } catch (@NonNull final Exception e) {
                                e.printStackTrace();
                                RequestLoginActivity.this.finish();
                            }
                        } else {
                            RequestLoginActivity.this.finish();
                        }
                    }
                });
            }

            @Override
            public void onFailure(@NonNull final Throwable t) {
                t.printStackTrace();
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (btchipDialog != null) {
            btchipDialog.dismiss();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        setResult(resultCode, data);
        finish();
    }

    @Override
    public void onResume() {
        super.onResume();

        getGAApp().getConnectionObservable().addObserver(this);
        tagDispatcher.enableExclusiveNfc();
    }

    @Override
    public void onPause() {
        super.onPause();
        getGAApp().getConnectionObservable().deleteObserver(this);
        tagDispatcher.disableExclusiveNfc();
    }

    @Override
    public void update(final Observable observable, final Object data) {

    }

    @NonNull
    private GreenAddressApplication getGAApp() {
        return (GreenAddressApplication) getApplication();
    }

    private GaService getGAService() {
        return getGAApp().gaService;
    }

    @Nullable
    private BTChipTransport getTransport(@Nullable final Tag t) {
    	BTChipTransport transport = null;
		if (t != null) {
			AndroidCard card = null;
			Log.d(TAG, "Start checking NFC transport");
			try {
				card = AndroidCard.get(t);
				transport = new BTChipTransportAndroidNFC(card);
                transport.setDebug(true);                            		                				
				transport.exchange(DUMMY_COMMAND).get();
				Log.d(TAG, "NFC transport checked");
			}
        	catch(Exception e) {
        		Log.d(TAG, "Tag was lost", e);
        		if (card != null) {
        			try {
        				transport.close();
        			}
        			catch(@NonNull final Exception e1) {
        			}
        			transport = null;
        		}
        	}                            	
		}
		return transport;    	
    }
    
    @Override
    public void tagDiscovered(Tag t) {    	
    	Log.d(TAG, "tagDiscovered " + t);
    	this.tag = t;
    	if (transportFuture != null) {
    		BTChipTransport transport = getTransport(t);
    		if (transport != null) {
    			if (transportFuture.set(transport)) {
    				if (nfcWaitDialog != null) {
    					RequestLoginActivity.this.runOnUiThread(new Runnable() {
    						@Override
    						public void run() {
    							nfcWaitDialog.hide();    							
    						}
    					});    					
    				}
    			}
    		}
    	}
    }
}
