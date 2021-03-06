package com.greenaddress.greenbits.ui;

import android.annotation.SuppressLint;
import android.content.ContextWrapper;
import android.support.annotation.NonNull;

import com.google.common.base.Charsets;
import com.lambdaworks.crypto.SCrypt;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DRMWorkaround;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

class MnemonicHelper {

    private static int levenshteinDistance(@NonNull final String sA, @NonNull final String sB) {
        final int s1 = sA.length() + 1;
        final int s2 = sB.length() + 1;

        int[] c = new int[s1];
        int[] nc = new int[s1];

        for (int j = 0; j < s1; ++j) c[j] = j;

        for (int j = 1; j < s2; ++j) {
            nc[0] = j;
            for(int k = 1; k < s1; ++k)
                nc[k] = Math.min(Math.min(c[k] + 1, nc[k - 1] + 1), c[k - 1]
                        + ((sA.charAt(k - 1) == sB.charAt(j - 1)) ? 0 : 1));
            final int[] swap = c; c = nc; nc = swap;
        }
        return c[s1 - 1];
    }

    static boolean isInvalidWord(final String word, final ContextWrapper ctxw, final boolean equals) throws IOException {
        final String[] words = getWords(ctxw);
        for (int i = 0; i < N_OF_WORDS; ++i) {
            if ((!equals && words[i].startsWith(word)) ||
                    (equals && words[i].equals(word))) {
                return false;
            }
        }
        return true;
    }

    static byte[] decryptMnemonic(@NonNull final byte[] entropy, @NonNull final String normalizedPassphrase) throws GeneralSecurityException {
        final byte[] salt = Arrays.copyOfRange(entropy, 32, 36);
        final byte[] encrypted = Arrays.copyOf(entropy, 32);
        final byte[] derived = SCrypt.scrypt(normalizedPassphrase.getBytes(Charsets.UTF_8), salt, 16384, 8, 8, 64);
        final byte[] key = Arrays.copyOfRange(derived, 32, 64);
        final SecretKeySpec keyspec = new SecretKeySpec(key, "AES");

        DRMWorkaround.maybeDisableExportControls();
        @SuppressLint("GetInstance") // ECB for 256 bits is enough, and is the same that BIP38 uses
        final Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");

        cipher.init(Cipher.DECRYPT_MODE, keyspec);
        final byte[] decrypted = cipher.doFinal(encrypted, 0, 32);
        for (int i = 0; i < 32; ++i)
            decrypted[i] ^= derived[i];

        final byte[] hash = Sha256Hash.twiceOf(decrypted).getBytes();
        if (!Arrays.equals(Arrays.copyOf(hash, 4), salt))
            throw new RuntimeException("Invalid checksum");
        return decrypted;
    }

    final private static int N_OF_WORDS = 2048;
    final private static String[] words = new String[N_OF_WORDS];

    private static String[] getWords(final ContextWrapper ctxw) throws IOException {
        if (words[0] == null) {
            final InputStream is = ctxw.getAssets().open("bip39-wordlist.txt");

            String line;
            int i = 0;
            try {
                final BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                while ((line = reader.readLine()) != null) {
                    words[i++] = line;
                }
            } finally {
                is.close();
            }
        }
        return words;
    }

    static String getClosestWord(@NonNull final String word, final ContextWrapper ctxw) throws IOException {

        final List<Integer> scores = new ArrayList<>(N_OF_WORDS);
        final String[] words = getWords(ctxw);
        for (int i = 0; i < N_OF_WORDS; ++i) {
            scores.add(levenshteinDistance(word, words[i]));
        }
        Integer min = Integer.MAX_VALUE;
        final List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < N_OF_WORDS; ++i) {
            final Integer score = scores.get(i);
            if (score.compareTo(min) < 0) {
                min = score;
                matches.clear();
                matches.add(i);
            } else if (score.compareTo(min) == 0) {
                matches.add(i);
            }
        }
        for (final Integer m : matches) {
            final String match = words[m];
            // give preference to words that start with our word
            if (match.startsWith(word)) {
                return match;
            }
        }
        for (final Integer m : matches) {
            final String match = words[m];
            // give preference to words that end with our word
            if (match.endsWith(word)) {
                return match;
            }
        }
        return words[matches.get(0)];
    }
}
