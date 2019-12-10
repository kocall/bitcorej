package org.bitcorej.chain.bitcoin;

import com.google.common.math.LongMath;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.Networks;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcorej.chain.ChainState;
import org.bitcorej.chain.KeyPair;
import org.bitcorej.chain.UTXOState;
import org.bitcorej.core.Network;
import org.bitcorej.utils.NumericUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.List;

public class BitcoinStateProvider implements ChainState, UTXOState {
    protected static final BigDecimal DECIMALS = new BigDecimal(10).pow(8);
    protected final static BigDecimal DUST_THRESHOLD = new BigDecimal(2730);

    protected Network network;
    protected NetworkParameters params;

    public BitcoinStateProvider(Network network) {
        switch (network) {
            case MAIN:
                params = MainNetParams.get();
                break;
            case TEST:
                params = TestNet3Params.get();
                break;
            case REGTEST:
                params = RegTestParams.get();
                break;
        }

        this.network = network;
    }

    public void setParams(NetworkParameters params) {
        this.params = params;
        Networks.register(this.params);
    }

    public String calcSegWitAddress(String legacyAddress) {
        byte[] pubKeyHash = Address.fromBase58(this.params, legacyAddress).getHash160();
        String redeemScript = String.format("0x0014%s", NumericUtil.bytesToHex(pubKeyHash));
        return Address.fromP2SHHash(this.params, Utils.sha256hash160(NumericUtil.hexToBytes(redeemScript))).toBase58();
    }

    public String calcRedeemScript(String segWitAddress) {
        byte[] pubKeyHash = Address.fromBase58(this.params, segWitAddress).getHash160();
        String redeemScript = String.format("0014%s", NumericUtil.bytesToHex(pubKeyHash));
        return redeemScript;
    }

    public String calcWitnessScript(String segWitAddress) {
        byte[] pubKeyHash = Address.fromBase58(this.params, segWitAddress).getHash160();
        byte[] scriptCode = NumericUtil.hexToBytes(String.format("0x1976a914%s88ac", NumericUtil.bytesToHex(pubKeyHash)));
        return NumericUtil.bytesToHex(scriptCode);
    }

    public String generateP2PKHScript(String address) {
        return NumericUtil.bytesToHex(ScriptBuilder.createOutputScript(Address.fromBase58(this.params, address)).getProgram());
    }

    @Override
    public KeyPair generateKeyPair(String secret) {
        ECKey ecKey = ECKey.fromPrivate(NumericUtil.hexToBytes(secret));
        return new KeyPair(ecKey.getPrivateKeyAsHex(), ecKey.toAddress(this.params).toString());
    }

    @Override
    public KeyPair generateKeyPair() {
        return this.generateKeyPair(new ECKey().getPrivateKeyAsHex());
    }

    @Override
    public Boolean validateTx(String rawTx, String tx) {
        return null;
    }

    @Override
    public org.bitcorej.chain.Transaction decodeRawTransaction(String rawTx) {
        return null;
    }

    public String encodeTransaction(List<UnspentOutput> utxos, List<Recipient> recipients, String changeAddress, BigDecimal fee) {
        return encodeTransaction(utxos, recipients, changeAddress, fee, DECIMALS);
    }

    public String encodeTransaction(List<UnspentOutput> utxos, List<Recipient> recipients, String changeAddress, BigDecimal fee, BigDecimal decimals) {
        JSONObject encodedTx = new JSONObject();

        BigDecimal totalInputAmount = new BigDecimal(0);
        JSONArray encodedInputs = new JSONArray();
        for (int i = 0; i < utxos.size(); i++) {
            UnspentOutput utxo = utxos.get(i);
            JSONObject encodedInput = new JSONObject();
            encodedInput.put("txid", utxo.getTxId());
            encodedInput.put("vout", utxo.getVout());
            JSONObject output = new JSONObject();
            String scriptPubKey = generateP2PKHScript(utxo.getAddress());
            output.put("script", scriptPubKey);
            BigDecimal amount = utxo.getAmount();
            output.put("amount", amount.toString());
            totalInputAmount = totalInputAmount.add(amount);
            encodedInput.put("output", output);
            encodedInputs.put(encodedInput);
        }

        BigDecimal totalOutputAmount = new BigDecimal(0);
        JSONArray encodedOutputs = new JSONArray();
        JSONArray destinations = new JSONArray();
        for (int i = 0; i < recipients.size(); i++) {
            Recipient recipient = recipients.get(i);
            JSONObject encodedOutput = new JSONObject();
            BigDecimal amount = recipient.getAmount();
            encodedOutput.put("amount", amount.toString());
            totalOutputAmount = totalOutputAmount.add(amount);

            String script = generateP2PKHScript(recipient.getAddress());
            encodedOutput.put("script", script);
            encodedOutputs.put(encodedOutput);
            destinations.put(recipient.toString());
        }

        if (totalInputAmount.compareTo(totalOutputAmount) < 1) {
            throw new RuntimeException("INSUFFICIENT FUNDS");
        }

        BigDecimal changeAmount = totalInputAmount.subtract(totalOutputAmount.add(fee));

        if (changeAmount.compareTo(DUST_THRESHOLD.divide(decimals)) > -1) {
            JSONObject encodedOutput = new JSONObject();
            encodedOutput.put("amount", changeAmount.toString());
            String script = generateP2PKHScript(changeAddress);
            // String script = NumericUtil.bytesToHex(ScriptBuilder.createOutputScript(Address.fromBase58(Address.getParametersFromAddress(changeAddress), changeAddress)).getProgram());
            encodedOutput.put("script", script);
            encodedOutputs.put(encodedOutput);
        }
        encodedTx.put("version", 1);
        encodedTx.put("inputs", encodedInputs);
        encodedTx.put("outputs", encodedOutputs);

        encodedTx.put("destinations", destinations);

        encodedTx.put("nLockTime", 0);
        return encodedTx.toString();
    }

    protected Transaction buildTransaction(String json) {
        JSONObject jsonObject = new JSONObject(json);
        Transaction tx = new Transaction(this.params);

        JSONArray inputs = jsonObject.getJSONArray("inputs");
        for (int i = 0; i < inputs.length(); i++) {
            JSONObject input = inputs.getJSONObject(i);
            String amountStr = input.getJSONObject("output").getString("amount");
            Long amount = new BigDecimal(amountStr).multiply(DECIMALS).longValue();
            Coin coin = Coin.valueOf(amount);
            TransactionInput txInput = new TransactionInput(this.params, tx, new Script(NumericUtil.hexToBytes(input.getJSONObject("output").getString("script"))).getProgram(), new TransactionOutPoint(params, input.getLong("vout"), Sha256Hash.wrap(input.getString("txid"))), coin);
            tx.addInput(txInput);

        }
        JSONArray outputs = jsonObject.getJSONArray("outputs");
        for (int i = 0; i < outputs.length(); i++) {
            JSONObject output = outputs.getJSONObject(i);
            Coin coin = Coin.valueOf(new BigDecimal(output.getString("amount")).multiply(BigDecimal.valueOf(LongMath.pow(10, 8))).longValue());
            tx.addOutput(new TransactionOutput(this.params, tx, coin, NumericUtil.hexToBytes(output.getString("script"))));
        }
        return tx;
    }

    public String toWIF(String privateKeyHex) {
        return ECKey.fromPrivate(NumericUtil.hexToBytes(privateKeyHex)).getPrivateKeyAsWiF(this.params);
    }

    protected String selectPrivateKeys(Script script, List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            String address = script.getToAddress(this.params).toString();
            String legacyAddress = this.generateKeyPair(keys.get(i)).getPublic();
            String segWitAddress = this.calcSegWitAddress(legacyAddress);
            if (address.equals(legacyAddress) || address.equals(segWitAddress)) {
                return keys.get(i);
            }
        }
        return null;
    }

    @Override
    public String signRawTransaction(String rawTx, List<String> keys) {
        JSONObject rawTxJSON = new JSONObject(rawTx);
        Transaction tx = buildTransaction(rawTx);

        for (int i = 0; i < tx.getInputs().size(); i++) {
            TransactionInput input = tx.getInput(i);
            Script scriptPubKey = new Script(input.getScriptBytes());

            ECKey ecKey = ECKey.fromPrivate(NumericUtil.hexToBytes(this.selectPrivateKeys(scriptPubKey, keys)));

            Sha256Hash hash = tx.hashForSignature(i, new Script(input.getScriptBytes()), Transaction.SigHash.ALL, false);
            ECKey.ECDSASignature ecSig = ecKey.sign(hash);
            TransactionSignature txSig = new TransactionSignature(ecSig, Transaction.SigHash.ALL, false);

            if (scriptPubKey.isSentToRawPubKey()) {
                input.setScriptSig(ScriptBuilder.createInputScript(txSig));
            } else {
                if (!scriptPubKey.isSentToAddress()) {
                    return null;
                }
                input.setScriptSig(ScriptBuilder.createInputScript(txSig, ecKey));
            }
        }

        JSONObject packedTx = new JSONObject();
        packedTx.put("txid", tx.getHashAsString());
        packedTx.put("raw", NumericUtil.bytesToHex(tx.bitcoinSerialize()));

        if (rawTxJSON.has("destinations")) {
            packedTx.put("destinations", rawTxJSON.getJSONArray("destinations"));
        }

        return packedTx.toString();
    }
}
