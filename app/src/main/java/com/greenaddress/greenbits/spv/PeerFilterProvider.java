package com.greenaddress.greenbits.spv;

import android.support.annotation.NonNull;

import com.greenaddress.greenbits.GaService;

import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;

import java.util.Set;

class PeerFilterProvider implements org.bitcoinj.core.PeerFilterProvider {
    private final GaService gaService;

    public PeerFilterProvider(final GaService gaService) {
        this.gaService = gaService;
    }

    @Override
    public long getEarliestKeyCreationTime() {
        return gaService.getClient().getLoginData().earliest_key_creation_time;
    }

    @Override
    public int getBloomFilterElementCount() {
        // 1 to avoid downloading full blocks (empty bloom filters are ignored by bitcoinj)

        return gaService.getUnspentOutputsOutpoints().isEmpty() ? 1: gaService.getUnspentOutputsOutpoints().size();
    }

    @NonNull
    @Override
    public BloomFilter getBloomFilter(final int size, final double falsePositiveRate, final long nTweak) {

        final BloomFilter res = new BloomFilter(size, falsePositiveRate, nTweak);
        final Set<Sha256Hash> set = gaService.getUnspentOutputsOutpoints().keySet();
        for (final Sha256Hash hash : set) {
            res.insert(Utils.reverseBytes(hash.getBytes()));
        }

        // add fake entry to avoid downloading blocks when filter is empty
        // (empty bloom filters are ignored by bitcoinj)

        if (set.isEmpty()) {
            res.insert(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef});
        }
        return res;
    }

    @Override
    public boolean isRequiringUpdateAllBloomFilter() {
        return false;
    }

    public void beginBloomFilterCalculation(){
        //TODO: ??
    }
    public void endBloomFilterCalculation(){
        //TODO: ??
    }
}
