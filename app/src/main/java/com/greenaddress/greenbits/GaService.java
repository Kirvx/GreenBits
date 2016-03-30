package com.greenaddress.greenbits;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.greenaddress.greenapi.INotificationHandler;
import com.greenaddress.greenapi.ISigningWallet;
import com.greenaddress.greenapi.LoginData;
import com.greenaddress.greenapi.Network;
import com.greenaddress.greenapi.PinData;
import com.greenaddress.greenapi.PreparedTransaction;
import com.greenaddress.greenapi.WalletClient;
import com.greenaddress.greenbits.spv.SPV;
import com.greenaddress.greenbits.ui.R;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Utils;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.utils.Fiat;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class GaService extends Service {

    @NonNull private static final String TAG = GaService.class.getSimpleName();
    @NonNull public final ListeningExecutorService es = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(3));
    @NonNull private final IBinder mBinder = new GaBinder(this);
    @NonNull final private Map<Integer, GaObservable> balanceObservables = new HashMap<>();
    @NonNull final private GaObservable newTransactionsObservable = new GaObservable();
    @NonNull final private GaObservable newTxVerifiedObservable = new GaObservable();
    public ListenableFuture<Void> onConnected;
    @NonNull
    public SettableFuture<Void> triggerOnFullyConnected =  SettableFuture.create();
    private Handler uiHandler;
    @Nullable
    private ListenableFuture<QrBitmap> latestQrBitmapMnemonics;
    @Nullable
    private ListenableFuture<String> latestMnemonics;
    private int curBlock = 0;

    private boolean reconnect = true;
    // cache
    private ListenableFuture<List<List<String>>> currencyExchangePairs;

    @NonNull private final Map<Integer, Coin> balancesCoin = new HashMap<>();

    @NonNull private final Map<Integer, Fiat> balancesFiat = new HashMap<>();
    private float fiatRate;
    private String fiatCurrency;
    private String fiatExchange;
    private ArrayList subaccounts;

    @Nullable
    private Map<Integer, DeterministicKey> gaDeterministicKeys;
    private String receivingId;
    private byte[] gaitPath;
    @Nullable
    private Map<?, ?> twoFacConfig;
    @NonNull private final GaObservable twoFacConfigObservable = new GaObservable();
    @Nullable
    private String deviceId;
    @Nullable
    public final SPV spv = new SPV(this);

    private int background_color;
    // fix me implement Preference change listener?
    // http://developer.android.com/guide/topics/ui/settings.html
    private int reconnectTimeout = 0;
    @Nullable
    private WalletClient client;
    @Nullable
    private ConnectivityObservable connectionObservable = null;
    @NonNull private final FutureCallback<LoginData> handleLoginData = new FutureCallback<LoginData>() {
        @Override
        public void onSuccess(@Nullable final LoginData result) {
            fiatCurrency = result.currency;
            fiatExchange = result.exchange;
            subaccounts = result.subaccounts;
            receivingId = result.receiving_id;
            gaitPath = Hex.decode(result.gait_path);

            balanceObservables.put(0, new GaObservable());
            updateBalance(0);
            for (final Object subaccount : result.subaccounts) {
                final Map<?, ?> subaccountMap = (Map) subaccount;
                final int pointer = ((Integer) subaccountMap.get("pointer"));
                balanceObservables.put(pointer, new GaObservable());
                updateBalance(pointer);
            }
            getAvailableTwoFacMethods();

            spv.resetUnspent();

            gaDeterministicKeys = new HashMap<>();

            spv.startIfEnabled();
            connectionObservable.setState(ConnectivityObservable.State.LOGGEDIN);
        }

        @Override
        public void onFailure(@NonNull final Throwable t) {
            t.printStackTrace();
            connectionObservable.setState(ConnectivityObservable.State.CONNECTED);
        }
    };

    @NonNull
    private static byte[] getRandomSeed() {
        final SecureRandom secureRandom = new SecureRandom();
        final byte[] seed = new byte[256 / 8];
        secureRandom.nextBytes(seed);
        return seed;
    }


    @NonNull
    public Observable getTwoFacConfigObservable() {
        return twoFacConfigObservable;
    }

    private void getAvailableTwoFacMethods() {
        Futures.addCallback(client.getTwoFacConfig(), new FutureCallback<Map<?, ?>>() {
            @Override
            public void onSuccess(@Nullable final Map<?, ?> result) {
                twoFacConfig = result;
                twoFacConfigObservable.setChanged();
                twoFacConfigObservable.notifyObservers();
            }

            @Override
            public void onFailure(@NonNull final Throwable t) {
                t.printStackTrace();
            }
        }, es);
    }

    public void resetSignUp() {
        latestMnemonics = null;
        latestQrBitmapMnemonics = null;
    }

    void reconnect() {
        Log.i(TAG, "Submitting reconnect after " + reconnectTimeout);
        onConnected = client.connect();
        connectionObservable.setState(ConnectivityObservable.State.CONNECTING);

        Futures.addCallback(onConnected, new FutureCallback<Void>() {
            @Override
            public void onSuccess(@Nullable final Void result) {
                connectionObservable.setState(ConnectivityObservable.State.CONNECTED);
                Log.i(TAG, "Success CONNECTED callback");
                triggerOnFullyConnected.set(null);
                if (!connectionObservable.getIsForcedLoggedOut() && !connectionObservable.getIsForcedTimeout() && client.canLogin()) {
                    login();
                }
            }

            @Override
            public void onFailure(@NonNull final Throwable t) {
                Log.i(TAG, "Failure throwable callback " + t.toString());
                connectionObservable.setState(ConnectivityObservable.State.DISCONNECTED);

                if (reconnectTimeout < ConnectivityObservable.RECONNECT_TIMEOUT_MAX) {
                    reconnectTimeout *= 1.2;
                }

                if (reconnectTimeout == 0) {
                    reconnectTimeout = ConnectivityObservable.RECONNECT_TIMEOUT;
                }

                // FIXME: handle delayed login
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        reconnect();
                    }
                }, reconnectTimeout);
            }
        }, es);
    }

    public boolean isValidAddress(final String address) {
        try {
            new org.bitcoinj.core.Address(Network.NETWORK, address);
            return true;
        } catch (@NonNull final AddressFormatException e) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        uiHandler = new Handler();

        try {
            MnemonicCode.INSTANCE = new MnemonicCode(getAssets().open("bip39-wordlist.txt"), null);
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.background_color = 0; // transparent
        connectionObservable = ((GreenAddressApplication) getApplication()).getConnectionObservable();


        deviceId = getSharedPreferences("service", MODE_PRIVATE).getString("device_id", null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            final SharedPreferences.Editor editor = getSharedPreferences("service", MODE_PRIVATE).edit();
            editor.putString("device_id", deviceId);
            editor.apply();
        }

        client = new WalletClient(new INotificationHandler() {
            @Override
            public void onNewBlock(final int count) {
                Log.i(TAG, "onNewBlock");
                if (getSharedPreferences("SPV", MODE_PRIVATE).getBoolean("enabled", true)) {
                    spv.addToBloomFilter(count, null, -1, -1, -1);
                }
                newTransactionsObservable.setChanged();
                newTransactionsObservable.notifyObservers();
            }

            @Override
            public void onNewTransaction(final int wallet_id, @NonNull final int[] subaccounts, final long value, final String txhash) {
                Log.i(TAG, "onNewTransactions");
                spv.updateUnspentOutputs();
                newTransactionsObservable.setChanged();
                newTransactionsObservable.notifyObservers();
                for (final int subaccount : subaccounts) {
                    updateBalance(subaccount);
                }
            }


            @Override
            public void onConnectionClosed(final int code) {
                gaDeterministicKeys = null;

                if (code == 4000) {
                    connectionObservable.setForcedLoggedOut();
                }
                connectionObservable.setState(ConnectivityObservable.State.DISCONNECTED);


                if (!connectionObservable.isNetworkUp()) {
                    // do nothing
                    connectionObservable.setState(ConnectivityObservable.State.OFFLINE);

                } else {

                    connectionObservable.setState(ConnectivityObservable.State.DISCONNECTED);

                    // 4000 (concurrentLoginOnDifferentDeviceId) && 4001 (concurrentLoginOnSameDeviceId!)
                    Log.i(TAG, "onConnectionClosed code=" + String.valueOf(code));
                    // FIXME: some callback to UI so you see what's happening.
                    // 1000 NORMAL_CLOSE
                    // 1006 SERVER_RESTART
                    reconnectTimeout = 0;
                    if (reconnect) {
                        reconnect();
                    }
                }
            }
        }, es);
        final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        final String proxyHost = sharedPref.getString("proxy_host", null);
        final String proxyPort = sharedPref.getString("proxy_port", null);

        if (proxyHost != null && proxyPort != null) {
            client.setProxy(proxyHost, proxyPort);
        }
    }

    @NonNull
    private ListenableFuture<Boolean> verifyP2SHSpendableBy(@NonNull final Script scriptHash, final Integer subaccount, final Integer pointer) {
        if (!scriptHash.isPayToScriptHash())
            return Futures.immediateFuture(false);
        final byte[] gotP2SH = scriptHash.getPubKeyHash();

        final List<ECKey> pubkeys = new ArrayList<>();
        final DeterministicKey gaWallet = getGaDeterministicKey(subaccount);
        final ECKey gaKey = HDKeyDerivation.deriveChildKey(gaWallet, new ChildNumber(pointer));
        pubkeys.add(gaKey);

        ISigningWallet userWallet = client.getHdWallet();
        if (subaccount != 0) {
            userWallet = userWallet.deriveChildKey(new ChildNumber(3, true));
            userWallet = userWallet.deriveChildKey(new ChildNumber(subaccount, true));
        }

        return Futures.transform(userWallet.getPubKey(), new Function<DeterministicKey, Boolean>() {
            @NonNull
            @Override
            public Boolean apply(final @Nullable DeterministicKey master) {
                final DeterministicKey derivedRoot = HDKeyDerivation.deriveChildKey(master, new ChildNumber(1));
                final DeterministicKey derivedPointer = HDKeyDerivation.deriveChildKey(derivedRoot, new ChildNumber(pointer));
                pubkeys.add(derivedPointer);

                String twoOfThreeBackupChaincode = null, twoOfThreeBackupPubkey = null;
                for (final Object subaccount_ : subaccounts) {
                    final Map<String, ?> subaccountMap = (Map) subaccount_;
                    if (subaccountMap.get("type").equals("2of3") && subaccountMap.get("pointer").equals(subaccount)) {
                        twoOfThreeBackupChaincode = (String) subaccountMap.get("2of3_backup_chaincode");
                        twoOfThreeBackupPubkey = (String) subaccountMap.get("2of3_backup_pubkey");
                    }
                }

                if (twoOfThreeBackupChaincode != null) {
                    final DeterministicKey backupWalletMaster = new DeterministicKey(
                            new ImmutableList.Builder<ChildNumber>().build(),
                            Hex.decode(twoOfThreeBackupChaincode),
                            ECKey.fromPublicOnly(Hex.decode(twoOfThreeBackupPubkey)).getPubKeyPoint(),
                            null, null);
                    final DeterministicKey derivedBackupRoot = HDKeyDerivation.deriveChildKey(backupWalletMaster, new ChildNumber(1));
                    final DeterministicKey derivedBackupPointer = HDKeyDerivation.deriveChildKey(derivedBackupRoot, new ChildNumber(pointer));
                    pubkeys.add(derivedBackupPointer);
                }

                final byte[] multisig = Script.createMultiSigOutputScript(2, pubkeys);

                if (client.getLoginData().segwit) {
                    // allow segwit p2sh only if segwit is enabled
                    ByteArrayOutputStream bits = new ByteArrayOutputStream();
                    bits.write(0);
                    try {
                        Script.writeBytes(bits, Sha256Hash.hash(multisig));
                    } catch (IOException e) {
                        throw new RuntimeException(e);  // cannot happen
                    }
                    if (Arrays.equals(gotP2SH, Utils.sha256hash160(bits.toByteArray()))) {
                        return true;
                    }
                }

                final byte[] expectedP2SH = Utils.sha256hash160(multisig);

                return Arrays.equals(gotP2SH, expectedP2SH);
            }
        });
    }

    @NonNull
    public ListenableFuture<Boolean> verifySpendableBy(@NonNull final TransactionOutput txOutput, final Integer subaccount, final Integer pointer) {
        return verifyP2SHSpendableBy(txOutput.getScriptPubKey(), subaccount, pointer);
    }

    private DeterministicKey getKeyPath(final DeterministicKey node) {
        int childNum;
        DeterministicKey nodePath = node;
        for (int i = 0; i < 32; ++i) {
            int b1 = gaitPath[i * 2];
            if (b1 < 0) {
                b1 = 256 + b1;
            }
            int b2 = gaitPath[i * 2 + 1];
            if (b2 < 0) {
                b2 = 256 + b2;
            }
            childNum = b1 * 256 + b2;
            nodePath = HDKeyDerivation.deriveChildKey(nodePath, new ChildNumber(childNum));
        }
        return nodePath;
    }

    private DeterministicKey getGaDeterministicKey(final Integer subaccount) {
        if (gaDeterministicKeys.keySet().contains(subaccount)) {
            return gaDeterministicKeys.get(subaccount);
        }

        final DeterministicKey nodePath = getKeyPath(HDKeyDerivation.deriveChildKey(new DeterministicKey(
                new ImmutableList.Builder<ChildNumber>().build(),
                Hex.decode(Network.depositChainCode),
                ECKey.fromPublicOnly(Hex.decode(Network.depositPubkey)).getPubKeyPoint(),
                null, null), new ChildNumber(subaccount != 0?3:1)));

        final DeterministicKey key = subaccount == 0 ? nodePath : HDKeyDerivation.deriveChildKey(nodePath, new ChildNumber(subaccount, false));
        gaDeterministicKeys.put(subaccount, key);
        return key;
    }

    @NonNull
    private ListenableFuture<LoginData> login() {
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);
        final ListenableFuture<LoginData> future = client.login(deviceId);
        Futures.addCallback(future, handleLoginData, es);
        return future;
    }

    @NonNull
    public ListenableFuture<LoginData> login(@NonNull final ISigningWallet signingWallet) {
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);

        final ListenableFuture<LoginData> future = client.login(signingWallet, deviceId);
        Futures.addCallback(future, handleLoginData, es);
        return future;
    }

    @NonNull
    public ListenableFuture<LoginData> login(@NonNull final String mnemonics) {
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);

        final ListenableFuture<LoginData> future = client.login(mnemonics, deviceId);
        Futures.addCallback(future, handleLoginData, es);
        return future;
    }

    @NonNull
    public ListenableFuture<LoginData> signup(@NonNull final String mnemonics) {
        final ListenableFuture<LoginData> signupFuture = client.loginRegister(mnemonics, deviceId);
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);

        Futures.addCallback(signupFuture, handleLoginData, es);
        return signupFuture;
    }

    @NonNull
    public ListenableFuture<LoginData> signup(final ISigningWallet signingWallet, @NonNull final byte[] masterPublicKey, @NonNull final byte[] masterChaincode, @NonNull final byte[] pathPublicKey, @NonNull final byte[] pathChaincode) {
        final ListenableFuture<LoginData> signupFuture = client.loginRegister(signingWallet, masterPublicKey, masterChaincode, pathPublicKey, pathChaincode, deviceId);
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);

        Futures.addCallback(signupFuture, handleLoginData, es);
        return signupFuture;
    }    

    @Nullable
    public String getMnemonics() {
        return client.getMnemonics();
    }

    @Nullable
    public WalletClient getClient() {
        return client;
    }

    @NonNull
    public ListenableFuture<LoginData> pinLogin(@NonNull final PinData pinData, final String pin) {
        connectionObservable.setState(ConnectivityObservable.State.LOGGINGIN);

        final ListenableFuture<LoginData> login = client.pinLogin(pinData, pin, deviceId);
        Futures.addCallback(login, handleLoginData, es);
        return login;
    }

    public void disconnect(final boolean reconnect) {
        this.reconnect = reconnect;
        spv.stopSPVSync();
        spv.tearDownSPV();
        for (final Integer key : balanceObservables.keySet()) {
            balanceObservables.get(key).deleteObservers();
        }
        client.disconnect();
        triggerOnFullyConnected =  SettableFuture.create();
        connectionObservable.setState(ConnectivityObservable.State.DISCONNECTED);
    }

    @NonNull
    public ListenableFuture<Map<?, ?>> updateBalance(final int subaccount) {
        final ListenableFuture<Map<?, ?>> future = client.getBalance(subaccount);
        Futures.addCallback(future, new FutureCallback<Map<?, ?>>() {
            @Override
            public void onSuccess(@Nullable final Map<?, ?> result) {
                balancesCoin.put(subaccount, Coin.valueOf(Long.valueOf((String) result.get("satoshi"))));
                fiatRate = Float.valueOf((String) result.get("fiat_exchange"));
                // Fiat.parseFiat uses toBigIntegerExact which requires at most 4 decimal digits,
                // while the server can return more, hence toBigInteger instead here:
                final BigInteger tmpValue = new BigDecimal((String) result.get("fiat_value"))
                        .movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).toBigInteger();
                // also strip extra decimals (over 2 places) because that's what the old JS client does
                final BigInteger fiatValue = tmpValue.subtract(tmpValue.mod(BigInteger.valueOf(10).pow(Fiat.SMALLEST_UNIT_EXPONENT - 2)));
                balancesFiat.put(subaccount, Fiat.valueOf((String) result.get("fiat_currency"), fiatValue.longValue()));

                fireBalanceChanged(subaccount);
            }

            @Override
            public void onFailure(@NonNull final Throwable t) {

            }
        }, es);
        return future;
    }

    @NonNull
    public ListenableFuture<Map<?, ?>> getSubaccountBalance(final int pointer) {
        return client.getSubaccountBalance(pointer);
    }

    public void fireBalanceChanged(final int subaccount) {
        if (balancesCoin != null && getBalanceCoin(subaccount) != null) {  // can be null if called from addUtxoToValues before balance is fetched
            balanceObservables.get(subaccount).setChanged();
            balanceObservables.get(subaccount).notifyObservers();
        }
    }

    @NonNull
    public ListenableFuture<Boolean> setPricingSource(final String currency, final String exchange) {
        return Futures.transform(client.setPricingSource(currency, exchange), new Function<Boolean, Boolean>() {
            @Override
            public Boolean apply(final Boolean input) {
                fiatCurrency = currency;
                fiatExchange = exchange;
                return input;
            }
        });
    }

    @NonNull
    public ListenableFuture<Map<?, ?>> getMyTransactions(final int subaccount) {
        return client.getMyTransactions(subaccount);
    }

    @NonNull
    public ListenableFuture<PinData> setPin(@NonNull final byte[] seed, final String mnemonic, final String pin, final String device_name) {
        return client.setPin(seed, mnemonic, pin, device_name);
    }

    private void preparePrivData(@NonNull final Map<String, Object> privateData) {
        final int subaccount = privateData.containsKey("subaccount")? (int) privateData.get("subaccount"):0;
        // skip fetching raw if not needed
        final Coin verifiedBalance = spv.verifiedBalancesCoin.get(subaccount);
        if (!getSharedPreferences("SPV", MODE_PRIVATE).getBoolean("enabled", true) ||  verifiedBalance == null || !verifiedBalance.equals(getBalanceCoin(subaccount)) || client.getHdWallet().requiresPrevoutRawTxs()) {
            privateData.put("prevouts_mode", "http");
        } else {
            privateData.put("prevouts_mode", "skip");
        }

        if (getAppearanceValue("replace_by_fee") != null) {
            privateData.put("rbf_optin", getAppearanceValue("replace_by_fee"));
        }
    }

    @NonNull
    public ListenableFuture<PreparedTransaction> prepareTx(@NonNull final Coin coinValue, final String recipient, @NonNull final Map<String, Object> privateData) {
        preparePrivData(privateData);
        return client.prepareTx(coinValue.longValue(), recipient, "sender", privateData);
    }

    @NonNull
    public ListenableFuture<PreparedTransaction> prepareSweepAll(final int subaccount, final String recipient, @NonNull final Map<String, Object> privData) {
        preparePrivData(privData);
        return client.prepareTx(
                getBalanceCoin(subaccount).longValue(),
                recipient, "receiver", privData
        );
    }

    @NonNull
    public ListenableFuture<String> signAndSendTransaction(@NonNull final PreparedTransaction prepared, final Object twoFacData) {
        return Futures.transform(client.signTransaction(prepared, false), new AsyncFunction<List<String>, String>() {
            @NonNull
            @Override
            public ListenableFuture<String> apply(@NonNull final List<String> input) throws Exception {
                return client.sendTransaction(input, twoFacData);
            }
        }, es);
    }

    @NonNull
    public ListenableFuture<String> sendTransaction(@NonNull final List<TransactionSignature> signatures, final Object twoFacData) {
        final List<String> signaturesStrings = new LinkedList<>();
        for (final TransactionSignature sig : signatures) {
            signaturesStrings.add(new String(Hex.encode(sig.encodeToBitcoin())));
        }
        return client.sendTransaction(signaturesStrings, twoFacData);
    }

    @NonNull
    public ListenableFuture<QrBitmap> getNewAddress(final int subaccount) {
        final AsyncFunction<Map, String> verifyAddress = new AsyncFunction<Map, String>() {
            @NonNull
            @Override
            public ListenableFuture<String> apply(@NonNull final Map input) throws Exception {
                final Integer pointer = ((Integer) input.get("pointer"));
                final byte[] script = Hex.decode((String) input.get("script")),
                             scriptHash;
                if (client.getLoginData().segwit) {
                    // allow segwit p2sh only if segwit is enabled
                    ByteArrayOutputStream bits = new ByteArrayOutputStream();
                    bits.write(0);
                    try {
                        Script.writeBytes(bits, Sha256Hash.hash(script));
                    } catch (IOException e) {
                        throw new RuntimeException(e);  // cannot happen
                    }
                    scriptHash = Utils.sha256hash160(bits.toByteArray());
                } else {
                    scriptHash = Utils.sha256hash160(script);
                }
                return Futures.transform(verifyP2SHSpendableBy(
                        ScriptBuilder.createP2SHOutputScript(scriptHash),
                        subaccount, pointer), new Function<Boolean, String>() {
                    @Nullable
                    @Override
                    public String apply(final @Nullable Boolean input) {
                        if (input) {
                            return Address.fromP2SHHash(Network.NETWORK, scriptHash).toString();
                        } else {
                            throw new IllegalArgumentException("Address validation failed");
                        }
                    }
                });
            }
        };
        final AsyncFunction<String, QrBitmap> addressToQr = new AsyncFunction<String, QrBitmap>() {
            @NonNull
            @Override
            public ListenableFuture<QrBitmap> apply(@NonNull final String input) {
                return es.submit(new QrBitmap(input, background_color));
            }
        };
        final ListenableFuture<String> verifiedAddress = Futures.transform(client.getNewAddress(subaccount), verifyAddress, es);
        return Futures.transform(verifiedAddress, addressToQr, es);
    }

    public ListenableFuture<List<List<String>>> getCurrencyExchangePairs() {
        if (currencyExchangePairs == null) {
            currencyExchangePairs = Futures.transform(client.getAvailableCurrencies(), new Function<Map<?, ?>, List<List<String>>>() {
                @Override
                public List<List<String>> apply(@Nullable final Map<?, ?> result) {
                    final Map<String, ArrayList<String>> per_exchange = (Map) result.get("per_exchange");
                    final List<List<String>> ret = new LinkedList<>();
                    for (final String exchange : per_exchange.keySet()) {
                        for (final String currency : per_exchange.get(exchange)) {
                            final ArrayList<String> currency_exchange = new ArrayList<>(2);
                            currency_exchange.add(currency);
                            currency_exchange.add(exchange);
                            ret.add(currency_exchange);
                        }
                    }
                    Collections.sort(ret, new Comparator<List<String>>() {
                        @Override
                        public int compare(final @NonNull List<String> lhs, @NonNull final List<String> rhs) {
                            return lhs.get(0).compareTo(rhs.get(0));
                        }
                    });
                    return ret;
                }
            }, es);
        }
        return currencyExchangePairs;
    }

    @Nullable
    public MnemonicCode getMnemonicCode() throws IOException {
        final InputStream closable = getApplicationContext().getAssets().open("bip39-wordlist.txt");
        try {
            return new MnemonicCode(closable, null);
        } finally {
            closable.close();
        }
    }

    @Nullable
    public ListenableFuture<String> getMnemonicPassphrase() {
        if (latestMnemonics == null) {
            latestMnemonics = es.submit(new Callable<String>() {
                public String call() throws IOException, MnemonicException.MnemonicLengthException {
                        return TextUtils.join(" ",
                                getMnemonicCode()
                                        .toMnemonic(getRandomSeed()));
                }
            });
            getQrCodeForMnemonicPassphrase();
        }
        return latestMnemonics;
    }


    public byte[] getEntropyFromMnemonics(@NonNull final String mnemonics) throws IOException, MnemonicException.MnemonicChecksumException, MnemonicException.MnemonicLengthException, MnemonicException.MnemonicWordException {
        final InputStream closable = getApplicationContext().getAssets().open("bip39-wordlist.txt");
        try {
            return new MnemonicCode(closable, null)
                            .toEntropy(Arrays.asList(mnemonics.split(" ")));
        } finally {
            closable.close();
        }
    }

    @Nullable
    public ListenableFuture<QrBitmap> getQrCodeForMnemonicPassphrase() {
        if (latestQrBitmapMnemonics == null) {
            Futures.addCallback(latestMnemonics, new FutureCallback<String>() {
                @Override
                public void onSuccess(@Nullable final String mnemonic) {
                    latestQrBitmapMnemonics = es.submit(new QrBitmap(mnemonic, Color.WHITE));
                }

                @Override
                public void onFailure(@NonNull final Throwable t) {

                }
            }, es);
        }
        return latestQrBitmapMnemonics;
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @NonNull
    public Map<Integer, Observable> getBalanceObservables() {
        final Map<Integer, Observable> ret = new HashMap<>();
        for (final Integer key : balanceObservables.keySet()) {
            ret.put(key, balanceObservables.get(key));
        }
        return ret;
    }

    @NonNull
    public Observable getNewTransactionsObservable() {
        return newTransactionsObservable;
    }

    @NonNull
    public Observable getNewTxVerifiedObservable() {
        return newTxVerifiedObservable;
    }


    public void notifyObservers(final Sha256Hash tx) {
        // FIXME: later spent outputs can be purged
        final SharedPreferences verified_utxo = getSharedPreferences("verified_utxo_" + getReceivingId(), MODE_PRIVATE);
        final SharedPreferences.Editor editor = verified_utxo.edit();
        editor.putBoolean(tx.toString(), true);
        editor.apply();
        spv.addUtxoToValues(tx);
        newTxVerifiedObservable.setChanged();
        newTxVerifiedObservable.notifyObservers();
    }

    public Coin getBalanceCoin(final int subaccount) {
        return balancesCoin.get(subaccount);
    }

    public Fiat getBalanceFiat(final int subaccount) {
        return balancesFiat.get(subaccount);
    }

    public float getFiatRate() {
        return fiatRate;
    }

    public String getFiatCurrency() {
        return fiatCurrency;
    }

    public String getFiatExchange() {
        return fiatExchange;
    }

    public ArrayList getSubaccounts() {
        return subaccounts;
    }

    public Object getAppearanceValue(@NonNull final String key) {
        return client.getAppearenceValue(key);
    }

    @Nullable
    public Map<?, ?> getTwoFacConfig() {
        return twoFacConfig;
    }

    /**
     * @param updateImmediately whether to not wait for server to reply before updating
     *                          the value in local settings dict (set false to wait)
     */
    @NonNull
    public ListenableFuture<Boolean> setAppearanceValue(@NonNull final String key, @NonNull final Object value, final boolean updateImmediately) {
        return client.setAppearanceValue(key, value, updateImmediately);
    }


    @NonNull
    public ListenableFuture<Object> requestTwoFacCode(@NonNull final String method, @NonNull final String action) {
        return client.requestTwoFacCode(method, action);
    }

    @NonNull
    public ListenableFuture<Object> requestTwoFacCode(@NonNull final String method, @NonNull final String action, @NonNull final Object data) {
        return client.requestTwoFacCode(method, action, data);
    }

    @NonNull
    public ListenableFuture<Map<?, ?>> prepareSweepSocial(@NonNull final byte[] pubKey, final boolean useElectrum) {
        return client.prepareSweepSocial(pubKey, useElectrum);
    }

    @NonNull
    public ListenableFuture<Map<?, ?>> processBip70URL(@NonNull final String url) {
        return client.processBip70URL(url);
    }

    @NonNull
    public ListenableFuture<PreparedTransaction> preparePayreq(@NonNull final Coin amount, @NonNull final Map<?, ?> data, @NonNull final Map<String, Object> privateData) {
        preparePrivData(privateData);
        return client.preparePayreq(amount, data, privateData);
    }

    public String getReceivingId() {
        return receivingId;
    }

    @NonNull
    public ListenableFuture<Boolean> initEnableTwoFac(@NonNull final String type, @NonNull final String details, @NonNull final Map<?, ?> twoFacData) {
        return client.initEnableTwoFac(type, details, twoFacData);
    }

    @NonNull
    public ListenableFuture<Boolean> enableTwoFac(@NonNull final String type, @NonNull final String code) {
        return Futures.transform(client.enableTwoFac(type, code), new Function<Boolean, Boolean>() {
            @Nullable
            @Override
            public Boolean apply(final @Nullable Boolean input) {
                getAvailableTwoFacMethods();
                return input;
            }
        });
    }

    @NonNull
    public ListenableFuture<Boolean> enableTwoFac(@NonNull final String type, @NonNull final String code, @NonNull final Object twoFacData) {
        return Futures.transform(client.enableTwoFac(type, code, twoFacData), new Function<Boolean, Boolean>() {
            @Nullable
            @Override
            public Boolean apply(final @Nullable Boolean input) {
                getAvailableTwoFacMethods();
                return input;
            }
        });
    }

    @NonNull
    public ListenableFuture<Boolean> disableTwoFac(@NonNull final String type, @NonNull final Map<String, String> twoFacData) {
        return Futures.transform(client.disableTwoFac(type, twoFacData), new Function<Boolean, Boolean>() {
            @Nullable
            @Override
            public Boolean apply(final @Nullable Boolean input) {
                getAvailableTwoFacMethods();
                return input;
            }
        });
    }

    @Nullable
    public List<String> getEnabledTwoFacNames(final boolean useSystemNames) {
        if (twoFacConfig == null) return null;
        final String[] allTwoFac = getResources().getStringArray(R.array.twoFactorChoices);
        final String[] allTwoFacSystem = getResources().getStringArray(R.array.twoFactorChoicesSystem);
        final ArrayList<String> enabledTwoFac = new ArrayList<>();
        for (int i = 0; i < allTwoFac.length; ++i) {
            if (((Boolean) twoFacConfig.get(allTwoFacSystem[i]))) {
                if (useSystemNames) {
                    enabledTwoFac.add(allTwoFacSystem[i]);
                } else {
                    enabledTwoFac.add(allTwoFac[i]);
                }
            }
        }
        return enabledTwoFac;
    }

    private static class GaObservable extends Observable {
        @Override
        public void setChanged() {
            super.setChanged();
        }
    }

    public int getCurBlock(){
        return curBlock;
    }

    public void setCurBlock(final int newBlock){
        curBlock = newBlock;
    }

    public boolean getSpvWiFiDialogShown(){
        return spv.spvWiFiDialogShown;
    }

    public void setSpvWiFiDialogShown(final boolean shown){
        spv.spvWiFiDialogShown = shown;
    }

    public Map<Sha256Hash, List<Integer>> getUnspentOutputsOutpoints() {
        return spv.unspentOutputsOutpoints;
    }
}
