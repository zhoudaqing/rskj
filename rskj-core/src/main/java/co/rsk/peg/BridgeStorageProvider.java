/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.peg;

import co.rsk.bitcoinj.core.*;
import co.rsk.config.BridgeConstants;
import co.rsk.crypto.Sha3Hash;
import org.ethereum.core.Repository;
import org.ethereum.rpc.TypeConverter;
import org.ethereum.vm.DataWord;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Provides an object oriented facade of the bridge contract memory.
 * @see co.rsk.remasc.RemascStorageProvider
 * @author ajlopez
 * @author Oscar Guindzberg
 */
public class BridgeStorageProvider {
    private static final DataWord ACTIVE_FEDERATION_BTC_UTXOS_KEY = new DataWord(TypeConverter.stringToByteArray("activeFederationBtcUTXOs"));
    private static final DataWord RETIRING_FEDERATION_BTC_UTXOS_KEY = new DataWord(TypeConverter.stringToByteArray("retiringFederationBtcUTXOs"));
    private static final DataWord BTC_TX_HASHES_ALREADY_PROCESSED_KEY = new DataWord(TypeConverter.stringToByteArray("btcTxHashesAP"));
    private static final DataWord RELEASE_REQUEST_QUEUE = new DataWord(TypeConverter.stringToByteArray("releaseRequestQueue"));
    private static final DataWord RELEASE_TX_SET = new DataWord(TypeConverter.stringToByteArray("releaseTransactionSet"));
    private static final DataWord RSK_TXS_WAITING_FOR_SIGNATURES_KEY = new DataWord(TypeConverter.stringToByteArray("rskTxsWaitingFS"));
    private static final DataWord BRIDGE_ACTIVE_FEDERATION_KEY = new DataWord(TypeConverter.stringToByteArray("bridgeActiveFederation"));
    private static final DataWord BRIDGE_RETIRING_FEDERATION_KEY = new DataWord(TypeConverter.stringToByteArray("bridgeRetiringFederation"));
    private static final DataWord BRIDGE_PENDING_FEDERATION_KEY = new DataWord(TypeConverter.stringToByteArray("bridgePendingFederation"));
    private static final DataWord BRIDGE_FEDERATION_ELECTION_KEY = new DataWord(TypeConverter.stringToByteArray("bridgeFederationElection"));
    private static final DataWord LOCK_WHITELIST_KEY = new DataWord(TypeConverter.stringToByteArray("bridgeLockWhitelist"));
    private static final DataWord FEE_PER_KB_KEY = new DataWord(TypeConverter.stringToByteArray("feePerKb"));

    private final Repository repository;
    private final byte[] contractAddress;
    private final NetworkParameters networkParameters;
    private final Context btcContext;

    private Map<Sha256Hash, Long> btcTxHashesAlreadyProcessed;

    // RSK release txs follow these steps: First, they are waiting for coin selection (releaseRequestQueue),
    // then they are waiting for enough confirmations on the RSK network (releaseTransactionSet),
    // then they are waiting for federators' signatures (rskTxsWaitingForSignatures),
    // then they are logged into the block that has them as completely signed for btc release
    // and are removed from rskTxsWaitingForSignatures.
    // key = rsk tx hash, value = btc tx
    private ReleaseRequestQueue releaseRequestQueue;
    private ReleaseTransactionSet releaseTransactionSet;
    private SortedMap<Sha3Hash, BtcTransaction> rskTxsWaitingForSignatures;

    private List<UTXO> activeFederationBtcUTXOs;
    private List<UTXO> retiringFederationBtcUTXOs;

    private Federation activeFederation;
    private Federation retiringFederation;
    private boolean shouldSaveRetiringFederation = false;
    private PendingFederation pendingFederation;
    private boolean shouldSavePendingFederation = false;

    private ABICallElection federationElection;

    private LockWhitelist lockWhitelist;

    public BridgeStorageProvider(Repository repository, String contractAddress, BridgeConstants bridgeConstants) {
        this.repository = repository;
        this.contractAddress = Hex.decode(contractAddress);
        this.networkParameters = bridgeConstants.getBtcParams();
        this.btcContext = new Context(networkParameters);
    }

    public List<UTXO> getActiveFederationBtcUTXOs() throws IOException {
        if (activeFederationBtcUTXOs != null) {
            return activeFederationBtcUTXOs;
        }

        activeFederationBtcUTXOs = getFromRepository(ACTIVE_FEDERATION_BTC_UTXOS_KEY, BridgeSerializationUtils::deserializeUTXOList);
        return activeFederationBtcUTXOs;
    }

    public void saveActiveFederationBtcUTXOs() throws IOException {
        if (activeFederationBtcUTXOs == null) {
            return;
        }

        saveToRepository(ACTIVE_FEDERATION_BTC_UTXOS_KEY, activeFederationBtcUTXOs, BridgeSerializationUtils::serializeUTXOList);
    }

    public List<UTXO> getRetiringFederationBtcUTXOs() throws IOException {
        if (retiringFederationBtcUTXOs != null) {
            return retiringFederationBtcUTXOs;
        }

        retiringFederationBtcUTXOs = getFromRepository(RETIRING_FEDERATION_BTC_UTXOS_KEY, BridgeSerializationUtils::deserializeUTXOList);
        return retiringFederationBtcUTXOs;
    }

    public void saveRetiringFederationBtcUTXOs() throws IOException {
        if (retiringFederationBtcUTXOs == null) {
            return;
        }

        saveToRepository(RETIRING_FEDERATION_BTC_UTXOS_KEY, retiringFederationBtcUTXOs, BridgeSerializationUtils::serializeUTXOList);
    }

    public Map<Sha256Hash, Long> getBtcTxHashesAlreadyProcessed() throws IOException {
        if (btcTxHashesAlreadyProcessed != null) {
            return btcTxHashesAlreadyProcessed;
        }

        btcTxHashesAlreadyProcessed = getFromRepository(BTC_TX_HASHES_ALREADY_PROCESSED_KEY, BridgeSerializationUtils::deserializeMapOfHashesToLong);
        return btcTxHashesAlreadyProcessed;
    }

    public void saveBtcTxHashesAlreadyProcessed() {
        if (btcTxHashesAlreadyProcessed == null) {
            return;
        }

        safeSaveToRepository(BTC_TX_HASHES_ALREADY_PROCESSED_KEY, btcTxHashesAlreadyProcessed, BridgeSerializationUtils::serializeMapOfHashesToLong);
    }

    public ReleaseRequestQueue getReleaseRequestQueue() throws IOException {
        if (releaseRequestQueue != null) {
            return releaseRequestQueue;
        }

        releaseRequestQueue = getFromRepository(
                RELEASE_REQUEST_QUEUE,
                data -> BridgeSerializationUtils.deserializeReleaseRequestQueue(data, networkParameters)
        );

        return releaseRequestQueue;
    }

    public void saveReleaseRequestQueue() {
        if (releaseRequestQueue == null) {
            return;
        }

        safeSaveToRepository(RELEASE_REQUEST_QUEUE, releaseRequestQueue, BridgeSerializationUtils::serializeReleaseRequestQueue);
    }

    public ReleaseTransactionSet getReleaseTransactionSet() throws IOException {
        if (releaseTransactionSet != null) {
            return releaseTransactionSet;
        }

        releaseTransactionSet = getFromRepository(
                RELEASE_TX_SET,
                data -> BridgeSerializationUtils.deserializeReleaseTransactionSet(data, networkParameters)
        );

        return releaseTransactionSet;
    }

    public void saveReleaseTransactionSet() {
        if (releaseTransactionSet == null) {
            return;
        }

        safeSaveToRepository(RELEASE_TX_SET, releaseTransactionSet, BridgeSerializationUtils::serializeReleaseTransactionSet);
    }

    public SortedMap<Sha3Hash, BtcTransaction> getRskTxsWaitingForSignatures() throws IOException {
        if (rskTxsWaitingForSignatures != null) {
            return rskTxsWaitingForSignatures;
        }

        rskTxsWaitingForSignatures = getFromRepository(
                RSK_TXS_WAITING_FOR_SIGNATURES_KEY,
                data -> BridgeSerializationUtils.deserializeMap(data, networkParameters, false)
        );
        return rskTxsWaitingForSignatures;
    }

    public void saveRskTxsWaitingForSignatures() {
        if (rskTxsWaitingForSignatures == null) {
            return;
        }

        safeSaveToRepository(RSK_TXS_WAITING_FOR_SIGNATURES_KEY, rskTxsWaitingForSignatures, BridgeSerializationUtils::serializeMap);
    }

    public Federation getActiveFederation() {
        if (activeFederation != null) {
            return activeFederation;
        }

        activeFederation = safeGetFromRepository(BRIDGE_ACTIVE_FEDERATION_KEY, data -> (data == null)? null :BridgeSerializationUtils.deserializeFederation(data, btcContext));
        return activeFederation;
    }

    public void setActiveFederation(Federation federation) {
        activeFederation = federation;
    }

    /**
     * Save the active federation
     * Only saved if a federation was set with BridgeStorageProvider::setActiveFederation
     */
    public void saveActiveFederation() {
        if (activeFederation == null) {
            return;
        }

        safeSaveToRepository(BRIDGE_ACTIVE_FEDERATION_KEY, activeFederation, BridgeSerializationUtils::serializeFederation);
    }

    public Federation getRetiringFederation() {
        if (retiringFederation != null) {
            return retiringFederation;
        }

        retiringFederation = safeGetFromRepository(BRIDGE_RETIRING_FEDERATION_KEY, data -> (data == null)? null : BridgeSerializationUtils.deserializeFederation(data, btcContext));
        return retiringFederation;
    }

    public void setRetiringFederation(Federation federation) {
        shouldSaveRetiringFederation = true;
        retiringFederation = federation;
    }

    /**
     * Save the retiring federation
     */
    public void saveRetiringFederation() {
        if (shouldSaveRetiringFederation) {
            safeSaveToRepository(BRIDGE_RETIRING_FEDERATION_KEY, retiringFederation, BridgeSerializationUtils::serializeFederation);
        }
    }

    public PendingFederation getPendingFederation() {
        if (pendingFederation != null) {
            return pendingFederation;
        }

        pendingFederation = safeGetFromRepository(BRIDGE_PENDING_FEDERATION_KEY, data -> (data == null)? null : BridgeSerializationUtils.deserializePendingFederation(data));
        return pendingFederation;
    }

    public void setPendingFederation(PendingFederation federation) {
        shouldSavePendingFederation = true;
        pendingFederation = federation;
    }

    /**
     * Save the pending federation
     */
    public void savePendingFederation() {
        if (shouldSavePendingFederation) {
            safeSaveToRepository(BRIDGE_PENDING_FEDERATION_KEY, pendingFederation, BridgeSerializationUtils::serializePendingFederation);
        }
    }

    /**
     * Save the federation election
     */
    public void saveFederationElection() {
        if (federationElection == null) {
            return;
        }

        safeSaveToRepository(BRIDGE_FEDERATION_ELECTION_KEY, federationElection, BridgeSerializationUtils::serializeElection);
    }

    public ABICallElection getFederationElection(AddressBasedAuthorizer authorizer) {
        if (federationElection != null) {
            return federationElection;
        }

        federationElection = safeGetFromRepository(BRIDGE_FEDERATION_ELECTION_KEY, data -> (data == null)? new ABICallElection(authorizer) : BridgeSerializationUtils.deserializeElection(data, authorizer));
        return federationElection;
    }

    /**
     * Save the lock whitelist
     */
    public void saveLockWhitelist() {
        if (lockWhitelist == null) {
            return;
        }

        safeSaveToRepository(LOCK_WHITELIST_KEY, lockWhitelist, BridgeSerializationUtils::serializeLockWhitelist);
    }

    public LockWhitelist getLockWhitelist() {
        if (lockWhitelist != null) {
            return lockWhitelist;
        }

        lockWhitelist = safeGetFromRepository(LOCK_WHITELIST_KEY,
            data -> (data == null)?
                new LockWhitelist(Collections.emptyList()) :
                BridgeSerializationUtils.deserializeLockWhitelist(data, btcContext.getParams())
        );

        return lockWhitelist;
    }

    public void save() throws IOException {
        saveBtcTxHashesAlreadyProcessed();

        saveReleaseRequestQueue();
        saveReleaseTransactionSet();
        saveRskTxsWaitingForSignatures();

        saveActiveFederation();
        saveActiveFederationBtcUTXOs();

        saveRetiringFederation();
        saveRetiringFederationBtcUTXOs();

        savePendingFederation();

        saveFederationElection();

        saveLockWhitelist();
    }

    private <T> T safeGetFromRepository(DataWord keyAddress, RepositoryDeserializer<T> deserializer) {
        try {
            return getFromRepository(keyAddress, deserializer);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to get from repository: " + keyAddress, ioe);
        }
    }

    private <T> T getFromRepository(DataWord keyAddress, RepositoryDeserializer<T> deserializer) throws IOException {
        byte[] data = repository.getStorageBytes(contractAddress, keyAddress);
        return deserializer.deserialize(data);
    }

    private <T> void safeSaveToRepository(DataWord addressKey, T object, RepositorySerializer<T> serializer) {
        try {
            saveToRepository(addressKey, object, serializer);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to save to repository: " + addressKey, ioe);
        }
    }

    private <T> void saveToRepository(DataWord addressKey, T object, RepositorySerializer<T> serializer) throws IOException {
        byte[] data = null;
        if (object != null) {
            data = serializer.serialize(object);
        }
        repository.addStorageBytes(contractAddress, addressKey, data);
    }

    private interface RepositoryDeserializer<T> {
        T deserialize(byte[] data) throws IOException;
    }

    private interface RepositorySerializer<T> {
        byte[] serialize(T object) throws IOException;
    }
}
