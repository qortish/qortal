package org.qortal.controller.arbitrary;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.ArbitraryDataFile;
import org.qortal.controller.Controller;
import org.qortal.data.network.ArbitraryPeerData;
import org.qortal.data.transaction.ArbitraryTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.*;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.settings.Settings;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;
import org.qortal.utils.Triple;

import java.security.SecureRandom;
import java.util.*;

public class ArbitraryDataFileManager extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataFileManager.class);

    private static ArbitraryDataFileManager instance;
    private volatile boolean isStopping = false;


    /**
     * Map to keep track of our in progress (outgoing) arbitrary data file requests
     */
    private Map<String, Long> arbitraryDataFileRequests = Collections.synchronizedMap(new HashMap<>());

    /**
     * Map to keep track of hashes that we might need to relay, keyed by the hash of the file (base58 encoded).
     * Value is comprised of the base58-encoded signature, the peer that is hosting it, and the timestamp that it was added
     */
    public Map<String, Triple<String, Peer, Long>> arbitraryRelayMap = Collections.synchronizedMap(new HashMap<>());


    private ArbitraryDataFileManager() {
    }

    public static ArbitraryDataFileManager getInstance() {
        if (instance == null)
            instance = new ArbitraryDataFileManager();

        return instance;
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Arbitrary Data File List Manager");

        try {
            while (!isStopping) {
                Thread.sleep(2000);

                // TODO
            }
        } catch (InterruptedException e) {
            // Fall-through to exit thread...
        }
    }

    public void shutdown() {
        isStopping = true;
        this.interrupt();
    }


    public void cleanupRequestCache(Long now) {
        if (now == null) {
            return;
        }
        final long requestMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_REQUEST_TIMEOUT;
        arbitraryDataFileRequests.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < requestMinimumTimestamp);

        final long relayMinimumTimestamp = now - ArbitraryDataManager.getInstance().ARBITRARY_RELAY_TIMEOUT;
        arbitraryRelayMap.entrySet().removeIf(entry -> entry.getValue().getC() == null || entry.getValue().getC() < relayMinimumTimestamp);
    }



    // Fetch data files by hash

    public boolean fetchAllArbitraryDataFiles(Repository repository, Peer peer, byte[] signature) {
        try {
            TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
            if (!(transactionData instanceof ArbitraryTransactionData))
                return false;

            ArbitraryTransactionData arbitraryTransactionData = (ArbitraryTransactionData) transactionData;

            // We use null to represent all hashes associated with this transaction
            return this.fetchArbitraryDataFiles(repository, peer, signature, arbitraryTransactionData, null);

        } catch (DataException e) {}

        return false;
    }

    public boolean fetchArbitraryDataFiles(Repository repository,
                                           Peer peer,
                                           byte[] signature,
                                           ArbitraryTransactionData arbitraryTransactionData,
                                           List<byte[]> hashes) throws DataException {

        // Load data file(s)
        ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(arbitraryTransactionData.getData(), signature);
        byte[] metadataHash = arbitraryTransactionData.getMetadataHash();
        arbitraryDataFile.setMetadataHash(metadataHash);

        // If hashes are null, we will treat this to mean all data hashes associated with this file
        if (hashes == null) {
            if (metadataHash == null) {
                // This transaction has no metadata/chunks, so use the main file hash
                hashes = Arrays.asList(arbitraryDataFile.getHash());
            }
            else if (!arbitraryDataFile.getMetadataFile().exists()) {
                // We don't have the metadata file yet, so request it
                hashes = Arrays.asList(arbitraryDataFile.getMetadataFile().getHash());
            }
            else {
                // Add the chunk hashes
                hashes = arbitraryDataFile.getChunkHashes();
            }
        }

        boolean receivedAtLeastOneFile = false;

        // Now fetch actual data from this peer
        for (byte[] hash : hashes) {
            if (!arbitraryDataFile.chunkExists(hash)) {
                // Only request the file if we aren't already requesting it from someone else
                if (!arbitraryDataFileRequests.containsKey(Base58.encode(hash))) {
                    ArbitraryDataFileMessage receivedArbitraryDataFileMessage = fetchArbitraryDataFile(peer, null, signature, hash, null);
                    if (receivedArbitraryDataFileMessage != null) {
                        LOGGER.info("Received data file {} from peer {}", receivedArbitraryDataFileMessage.getArbitraryDataFile().getHash58(), peer);
                        receivedAtLeastOneFile = true;
                    }
                    else {
                        LOGGER.info("Peer {} didn't respond with data file {} for signature {}", peer, Base58.encode(hash), Base58.encode(signature));
                    }
                }
                else {
                    LOGGER.info("Already requesting data file {} for signature {}", arbitraryDataFile, Base58.encode(signature));
                }
            }
        }

        if (receivedAtLeastOneFile) {
            // Update our lookup table to indicate that this peer holds data for this signature
            String peerAddress = peer.getPeerData().getAddress().toString();
            LOGGER.info("Adding arbitrary peer: {} for signature {}", peerAddress, Base58.encode(signature));
            ArbitraryPeerData arbitraryPeerData = new ArbitraryPeerData(signature, peer);
            repository.discardChanges();
            repository.getArbitraryRepository().save(arbitraryPeerData);
            repository.saveChanges();

            // Invalidate the hosted transactions cache as we are now hosting something new
            ArbitraryDataStorageManager.getInstance().invalidateHostedTransactionsCache();
        }

        // Check if we have all the files we need for this transaction
        if (arbitraryDataFile.allFilesExist()) {

            // We have all the chunks for this transaction, so we should invalidate the transaction's name's
            // data cache so that it is rebuilt the next time we serve it
            ArbitraryDataManager.getInstance().invalidateCache(arbitraryTransactionData);

            // We may also need to broadcast to the network that we are now hosting files for this transaction,
            // but only if these files are in accordance with our storage policy
            if (ArbitraryDataStorageManager.getInstance().canStoreData(arbitraryTransactionData)) {
                // Use a null peer address to indicate our own
                Message newArbitrarySignatureMessage = new ArbitrarySignaturesMessage(null, Arrays.asList(signature));
                Network.getInstance().broadcast(broadcastPeer -> newArbitrarySignatureMessage);
            }
        }

        return receivedAtLeastOneFile;
    }

    private ArbitraryDataFileMessage fetchArbitraryDataFile(Peer peer, Peer requestingPeer, byte[] signature, byte[] hash, Message originalMessage) throws DataException {
        ArbitraryDataFile existingFile = ArbitraryDataFile.fromHash(hash, signature);
        boolean fileAlreadyExists = existingFile.exists();
        Message message = null;

        // Fetch the file if it doesn't exist locally
        if (!fileAlreadyExists) {
            String hash58 = Base58.encode(hash);
            LOGGER.info(String.format("Fetching data file %.8s from peer %s", hash58, peer));
            arbitraryDataFileRequests.put(hash58, NTP.getTime());
            Message getArbitraryDataFileMessage = new GetArbitraryDataFileMessage(signature, hash);

            try {
                message = peer.getResponseWithTimeout(getArbitraryDataFileMessage, (int) ArbitraryDataManager.ARBITRARY_REQUEST_TIMEOUT);
            } catch (InterruptedException e) {
                // Will return below due to null message
            }
            arbitraryDataFileRequests.remove(hash58);
            LOGGER.trace(String.format("Removed hash %.8s from arbitraryDataFileRequests", hash58));

            if (message == null || message.getType() != Message.MessageType.ARBITRARY_DATA_FILE) {
                return null;
            }
        }
        ArbitraryDataFileMessage arbitraryDataFileMessage = (ArbitraryDataFileMessage) message;

        // We might want to forward the request to the peer that originally requested it
        this.handleArbitraryDataFileForwarding(requestingPeer, message, originalMessage);

        boolean isRelayRequest = (requestingPeer != null);
        if (isRelayRequest) {
            if (!fileAlreadyExists) {
                // File didn't exist locally before the request, and it's a forwarding request, so delete it
                LOGGER.info("Deleting file {} because it was needed for forwarding only", Base58.encode(hash));
                ArbitraryDataFile dataFile = arbitraryDataFileMessage.getArbitraryDataFile();
                dataFile.delete();
            }
        }

        return arbitraryDataFileMessage;
    }


    public void handleArbitraryDataFileForwarding(Peer requestingPeer, Message message, Message originalMessage) {
        // Return if there is no originally requesting peer to forward to
        if (requestingPeer == null) {
            return;
        }

        // Return if we're not in relay mode or if this request doesn't need forwarding
        if (!Settings.getInstance().isRelayModeEnabled()) {
            return;
        }

        LOGGER.info("Received arbitrary data file - forwarding is needed");

        // The ID needs to match that of the original request
        message.setId(originalMessage.getId());

        if (!requestingPeer.sendMessage(message)) {
            LOGGER.info("Failed to forward arbitrary data file to peer {}", requestingPeer);
            requestingPeer.disconnect("failed to forward arbitrary data file");
        }
        else {
            LOGGER.info("Forwarded arbitrary data file to peer {}", requestingPeer);
        }
    }


    // Fetch data directly from peers

    public boolean fetchDataFilesFromPeersForSignature(byte[] signature) {
        String signature58 = Base58.encode(signature);
        ArbitraryDataFileListManager.getInstance().addToSignatureRequests(signature58, false, true);

        // Firstly fetch peers that claim to be hosting files for this signature
        try (final Repository repository = RepositoryManager.getRepository()) {

            List<ArbitraryPeerData> peers = repository.getArbitraryRepository().getArbitraryPeerDataForSignature(signature);
            if (peers == null || peers.isEmpty()) {
                LOGGER.info("No peers found for signature {}", signature58);
                return false;
            }

            LOGGER.info("Attempting a direct peer connection for signature {}...", signature58);

            // Peers found, so pick a random one and request data from it
            int index = new SecureRandom().nextInt(peers.size());
            ArbitraryPeerData arbitraryPeerData = peers.get(index);
            String peerAddressString = arbitraryPeerData.getPeerAddress();
            return Network.getInstance().requestDataFromPeer(peerAddressString, signature);

        } catch (DataException e) {
            LOGGER.info("Unable to fetch peer list from repository");
        }

        return false;
    }


    // Network handlers

    public void onNetworkGetArbitraryDataFileMessage(Peer peer, Message message) {
        // Don't respond if QDN is disabled
        if (!Settings.getInstance().isQdnEnabled()) {
            return;
        }

        GetArbitraryDataFileMessage getArbitraryDataFileMessage = (GetArbitraryDataFileMessage) message;
        byte[] hash = getArbitraryDataFileMessage.getHash();
        String hash58 = Base58.encode(hash);
        byte[] signature = getArbitraryDataFileMessage.getSignature();
        Controller.getInstance().stats.getArbitraryDataFileMessageStats.requests.incrementAndGet();

        LOGGER.info("Received GetArbitraryDataFileMessage from peer {} for hash {}", peer, Base58.encode(hash));

        try {
            ArbitraryDataFile arbitraryDataFile = ArbitraryDataFile.fromHash(hash, signature);
            Triple<String, Peer, Long> relayInfo = this.arbitraryRelayMap.get(hash58);

            if (arbitraryDataFile.exists()) {
                LOGGER.info("Hash {} exists", hash58);

                // We can serve the file directly as we already have it
                ArbitraryDataFileMessage arbitraryDataFileMessage = new ArbitraryDataFileMessage(signature, arbitraryDataFile);
                arbitraryDataFileMessage.setId(message.getId());
                if (!peer.sendMessage(arbitraryDataFileMessage)) {
                    LOGGER.info("Couldn't sent file");
                    peer.disconnect("failed to send file");
                }
                LOGGER.info("Sent file {}", arbitraryDataFile);
            }
            else if (relayInfo != null) {
                LOGGER.info("We have relay info for hash {}", Base58.encode(hash));
                // We need to ask this peer for the file
                Peer peerToAsk = relayInfo.getB();
                if (peerToAsk != null) {

                    // Forward the message to this peer
                    LOGGER.info("Asking peer {} for hash {}", peerToAsk, hash58);
                    this.fetchArbitraryDataFile(peerToAsk, peer, signature, hash, message);

                    // Remove from the map regardless of outcome, as the relay attempt is now considered complete
                    arbitraryRelayMap.remove(hash58);
                }
                else {
                    LOGGER.info("Peer {} not found in relay info", peer);
                }
            }
            else {
                LOGGER.info("Hash {} doesn't exist and we don't have relay info", hash58);

                // We don't have this file
                Controller.getInstance().stats.getArbitraryDataFileMessageStats.unknownFiles.getAndIncrement();

                // Send valid, yet unexpected message type in response, so peer's synchronizer doesn't have to wait for timeout
                LOGGER.debug(String.format("Sending 'file unknown' response to peer %s for GET_FILE request for unknown file %s", peer, arbitraryDataFile));

                // We'll send empty block summaries message as it's very short
                // TODO: use a different message type here
                Message fileUnknownMessage = new BlockSummariesMessage(Collections.emptyList());
                fileUnknownMessage.setId(message.getId());
                if (!peer.sendMessage(fileUnknownMessage)) {
                    LOGGER.info("Couldn't sent file-unknown response");
                    peer.disconnect("failed to send file-unknown response");
                }
                else {
                    LOGGER.info("Sent file-unknown response for file {}", arbitraryDataFile);
                }
            }
        }
        catch (DataException e) {
            LOGGER.info("Unable to handle request for arbitrary data file: {}", hash58);
        }
    }

}
