package org.qortal.controller;

import com.google.common.primitives.Longs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.Account;
import org.qortal.account.PrivateKeyAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.block.BlockChain;
import org.qortal.data.account.MintingAccountData;
import org.qortal.data.account.RewardShareData;
import org.qortal.data.network.OnlineAccountData;
import org.qortal.network.Network;
import org.qortal.network.Peer;
import org.qortal.network.message.GetOnlineAccountsMessage;
import org.qortal.network.message.Message;
import org.qortal.network.message.OnlineAccountsMessage;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;
import org.qortal.utils.NTP;

import java.util.*;
import java.util.stream.Collectors;

public class OnlineAccountsManager {

    private static final Logger LOGGER = LogManager.getLogger(OnlineAccountsManager.class);

    private static OnlineAccountsManager instance;

    // To do with online accounts list
    private static final long ONLINE_ACCOUNTS_TASKS_INTERVAL = 10 * 1000L; // ms
    private static final long ONLINE_ACCOUNTS_BROADCAST_INTERVAL = 1 * 60 * 1000L; // ms
    public static final long ONLINE_TIMESTAMP_MODULUS = 5 * 60 * 1000L;
    private static final long LAST_SEEN_EXPIRY_PERIOD = (ONLINE_TIMESTAMP_MODULUS * 2) + (1 * 60 * 1000L);
    /** How many (latest) blocks' worth of online accounts we cache */
    private static final int MAX_BLOCKS_CACHED_ONLINE_ACCOUNTS = 2;

    private long onlineAccountsTasksTimestamp = Controller.startTime + ONLINE_ACCOUNTS_TASKS_INTERVAL; // ms

    /** Cache of current 'online accounts' */
    List<OnlineAccountData> onlineAccounts = new ArrayList<>();
    /** Cache of latest blocks' online accounts */
    Deque<List<OnlineAccountData>> latestBlocksOnlineAccounts = new ArrayDeque<>(MAX_BLOCKS_CACHED_ONLINE_ACCOUNTS);

    public OnlineAccountsManager() {

    }

    public static synchronized OnlineAccountsManager getInstance() {
        if (instance == null) {
            instance = new OnlineAccountsManager();
        }

        return instance;
    }


    public void checkOnlineAccountsTasks(long now) {
        // Perform tasks to do with managing online accounts list
        if (now >= onlineAccountsTasksTimestamp) {
            onlineAccountsTasksTimestamp = now + ONLINE_ACCOUNTS_TASKS_INTERVAL;
            performOnlineAccountsTasks();
        }
    }

    private void sendOurOnlineAccountsInfo() {
        final Long now = NTP.getTime();
        if (now == null)
            return;

        List<MintingAccountData> mintingAccounts;
        try (final Repository repository = RepositoryManager.getRepository()) {
            mintingAccounts = repository.getAccountRepository().getMintingAccounts();

            // We have no accounts, but don't reset timestamp
            if (mintingAccounts.isEmpty())
                return;

            // Only reward-share accounts allowed
            Iterator<MintingAccountData> iterator = mintingAccounts.iterator();
            while (iterator.hasNext()) {
                MintingAccountData mintingAccountData = iterator.next();

                RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(mintingAccountData.getPublicKey());
                if (rewardShareData == null) {
                    // Reward-share doesn't even exist - probably not a good sign
                    iterator.remove();
                    continue;
                }

                Account mintingAccount = new Account(repository, rewardShareData.getMinter());
                if (!mintingAccount.canMint()) {
                    // Minting-account component of reward-share can no longer mint - disregard
                    iterator.remove();
                    continue;
                }
            }
        } catch (DataException e) {
            LOGGER.warn(String.format("Repository issue trying to fetch minting accounts: %s", e.getMessage()));
            return;
        }

        // 'current' timestamp
        final long onlineAccountsTimestamp = OnlineAccountsManager.toOnlineAccountTimestamp(now);
        boolean hasInfoChanged = false;

        byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);
        List<OnlineAccountData> ourOnlineAccounts = new ArrayList<>();

        MINTING_ACCOUNTS:
        for (MintingAccountData mintingAccountData : mintingAccounts) {
            PrivateKeyAccount mintingAccount = new PrivateKeyAccount(null, mintingAccountData.getPrivateKey());

            byte[] signature = mintingAccount.sign(timestampBytes);
            byte[] publicKey = mintingAccount.getPublicKey();

            // Our account is online
            OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey);
            synchronized (this.onlineAccounts) {
                Iterator<OnlineAccountData> iterator = this.onlineAccounts.iterator();
                while (iterator.hasNext()) {
                    OnlineAccountData existingOnlineAccountData = iterator.next();

                    if (Arrays.equals(existingOnlineAccountData.getPublicKey(), ourOnlineAccountData.getPublicKey())) {
                        // If our online account is already present, with same timestamp, then move on to next mintingAccount
                        if (existingOnlineAccountData.getTimestamp() == onlineAccountsTimestamp)
                            continue MINTING_ACCOUNTS;

                        // If our online account is already present, but with older timestamp, then remove it
                        iterator.remove();
                        break;
                    }
                }

                this.onlineAccounts.add(ourOnlineAccountData);
            }

            LOGGER.trace(() -> String.format("Added our online account %s with timestamp %d", mintingAccount.getAddress(), onlineAccountsTimestamp));
            ourOnlineAccounts.add(ourOnlineAccountData);
            hasInfoChanged = true;
        }

        if (!hasInfoChanged)
            return;

        Message message = new OnlineAccountsMessage(ourOnlineAccounts);
        Network.getInstance().broadcast(peer -> message);

        LOGGER.trace(()-> String.format("Broadcasted %d online account%s with timestamp %d", ourOnlineAccounts.size(), (ourOnlineAccounts.size() != 1 ? "s" : ""), onlineAccountsTimestamp));
    }

    private void performOnlineAccountsTasks() {
        final Long now = NTP.getTime();
        if (now == null)
            return;

        // Expire old entries
        final long cutoffThreshold = now - LAST_SEEN_EXPIRY_PERIOD;
        synchronized (this.onlineAccounts) {
            Iterator<OnlineAccountData> iterator = this.onlineAccounts.iterator();
            while (iterator.hasNext()) {
                OnlineAccountData onlineAccountData = iterator.next();

                if (onlineAccountData.getTimestamp() < cutoffThreshold) {
                    iterator.remove();

                    LOGGER.trace(() -> {
                        PublicKeyAccount otherAccount = new PublicKeyAccount(null, onlineAccountData.getPublicKey());
                        return String.format("Removed expired online account %s with timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp());
                    });
                }
            }
        }

        // Request data from other peers?
        if ((this.onlineAccountsTasksTimestamp % ONLINE_ACCOUNTS_BROADCAST_INTERVAL) < ONLINE_ACCOUNTS_TASKS_INTERVAL) {
            Message message;
            synchronized (this.onlineAccounts) {
                message = new GetOnlineAccountsMessage(this.onlineAccounts);
            }
            Network.getInstance().broadcast(peer -> message);
        }

        // Refresh our online accounts signatures?
        sendOurOnlineAccountsInfo();
    }

    public static long toOnlineAccountTimestamp(long timestamp) {
        return (timestamp / ONLINE_TIMESTAMP_MODULUS) * ONLINE_TIMESTAMP_MODULUS;
    }

    /** Returns list of online accounts with timestamp recent enough to be considered currently online. */
    public List<OnlineAccountData> getOnlineAccounts() {
        final long onlineTimestamp = OnlineAccountsManager.toOnlineAccountTimestamp(NTP.getTime());

        synchronized (this.onlineAccounts) {
            return this.onlineAccounts.stream().filter(account -> account.getTimestamp() == onlineTimestamp).collect(Collectors.toList());
        }
    }

    /** Returns cached, unmodifiable list of latest block's online accounts. */
    public List<OnlineAccountData> getLatestBlocksOnlineAccounts() {
        synchronized (this.latestBlocksOnlineAccounts) {
            return this.latestBlocksOnlineAccounts.peekFirst();
        }
    }

    /** Caches list of latest block's online accounts. Typically called by Block.process() */
    public void pushLatestBlocksOnlineAccounts(List<OnlineAccountData> latestBlocksOnlineAccounts) {
        synchronized (this.latestBlocksOnlineAccounts) {
            if (this.latestBlocksOnlineAccounts.size() == MAX_BLOCKS_CACHED_ONLINE_ACCOUNTS)
                this.latestBlocksOnlineAccounts.pollLast();

            this.latestBlocksOnlineAccounts.addFirst(latestBlocksOnlineAccounts == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(latestBlocksOnlineAccounts));
        }
    }

    /** Reverts list of latest block's online accounts. Typically called by Block.orphan() */
    public void popLatestBlocksOnlineAccounts() {
        synchronized (this.latestBlocksOnlineAccounts) {
            this.latestBlocksOnlineAccounts.pollFirst();
        }
    }


    // Utilities

    private void verifyAndAddAccount(Repository repository, OnlineAccountData onlineAccountData) throws DataException {
        final Long now = NTP.getTime();
        if (now == null)
            return;

        PublicKeyAccount otherAccount = new PublicKeyAccount(repository, onlineAccountData.getPublicKey());

        // Check timestamp is 'recent' here
        if (Math.abs(onlineAccountData.getTimestamp() - now) > ONLINE_TIMESTAMP_MODULUS * 2) {
            LOGGER.trace(() -> String.format("Rejecting online account %s with out of range timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp()));
            return;
        }

        // Verify
        byte[] data = Longs.toByteArray(onlineAccountData.getTimestamp());
        if (!otherAccount.verify(onlineAccountData.getSignature(), data)) {
            LOGGER.trace(() -> String.format("Rejecting invalid online account %s", otherAccount.getAddress()));
            return;
        }

        // Qortal: check online account is actually reward-share
        RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(onlineAccountData.getPublicKey());
        if (rewardShareData == null) {
            // Reward-share doesn't even exist - probably not a good sign
            LOGGER.trace(() -> String.format("Rejecting unknown online reward-share public key %s", Base58.encode(onlineAccountData.getPublicKey())));
            return;
        }

        Account mintingAccount = new Account(repository, rewardShareData.getMinter());
        if (!mintingAccount.canMint()) {
            // Minting-account component of reward-share can no longer mint - disregard
            LOGGER.trace(() -> String.format("Rejecting online reward-share with non-minting account %s", mintingAccount.getAddress()));
            return;
        }

        synchronized (this.onlineAccounts) {
            OnlineAccountData existingAccountData = this.onlineAccounts.stream().filter(account -> Arrays.equals(account.getPublicKey(), onlineAccountData.getPublicKey())).findFirst().orElse(null);

            if (existingAccountData != null) {
                if (existingAccountData.getTimestamp() < onlineAccountData.getTimestamp()) {
                    this.onlineAccounts.remove(existingAccountData);

                    LOGGER.trace(() -> String.format("Updated online account %s with timestamp %d (was %d)", otherAccount.getAddress(), onlineAccountData.getTimestamp(), existingAccountData.getTimestamp()));
                } else {
                    LOGGER.trace(() -> String.format("Not updating existing online account %s", otherAccount.getAddress()));

                    return;
                }
            } else {
                LOGGER.trace(() -> String.format("Added online account %s with timestamp %d", otherAccount.getAddress(), onlineAccountData.getTimestamp()));
            }

            this.onlineAccounts.add(onlineAccountData);
        }
    }

    public void ensureTestingAccountsOnline(PrivateKeyAccount... onlineAccounts) {
        if (!BlockChain.getInstance().isTestChain()) {
            LOGGER.warn("Ignoring attempt to ensure test account is online for non-test chain!");
            return;
        }

        final Long now = NTP.getTime();
        if (now == null)
            return;

        final long onlineAccountsTimestamp = this.toOnlineAccountTimestamp(now);
        byte[] timestampBytes = Longs.toByteArray(onlineAccountsTimestamp);

        synchronized (this.onlineAccounts) {
            this.onlineAccounts.clear();

            for (PrivateKeyAccount onlineAccount : onlineAccounts) {
                // Check mintingAccount is actually reward-share?

                byte[] signature = onlineAccount.sign(timestampBytes);
                byte[] publicKey = onlineAccount.getPublicKey();

                OnlineAccountData ourOnlineAccountData = new OnlineAccountData(onlineAccountsTimestamp, signature, publicKey);
                this.onlineAccounts.add(ourOnlineAccountData);
            }
        }
    }


    // Network handlers

    public void onNetworkGetOnlineAccountsMessage(Peer peer, Message message) {
        GetOnlineAccountsMessage getOnlineAccountsMessage = (GetOnlineAccountsMessage) message;

        List<OnlineAccountData> excludeAccounts = getOnlineAccountsMessage.getOnlineAccounts();

        // Send online accounts info, excluding entries with matching timestamp & public key from excludeAccounts
        List<OnlineAccountData> accountsToSend;
        synchronized (this.onlineAccounts) {
            accountsToSend = new ArrayList<>(this.onlineAccounts);
        }

        Iterator<OnlineAccountData> iterator = accountsToSend.iterator();

        SEND_ITERATOR:
        while (iterator.hasNext()) {
            OnlineAccountData onlineAccountData = iterator.next();

            for (int i = 0; i < excludeAccounts.size(); ++i) {
                OnlineAccountData excludeAccountData = excludeAccounts.get(i);

                if (onlineAccountData.getTimestamp() == excludeAccountData.getTimestamp() && Arrays.equals(onlineAccountData.getPublicKey(), excludeAccountData.getPublicKey())) {
                    iterator.remove();
                    continue SEND_ITERATOR;
                }
            }
        }

        Message onlineAccountsMessage = new OnlineAccountsMessage(accountsToSend);
        peer.sendMessage(onlineAccountsMessage);

        LOGGER.trace(() -> String.format("Sent %d of our %d online accounts to %s", accountsToSend.size(), this.onlineAccounts.size(), peer));
    }

    public void onNetworkOnlineAccountsMessage(Peer peer, Message message) {
        OnlineAccountsMessage onlineAccountsMessage = (OnlineAccountsMessage) message;

        List<OnlineAccountData> peersOnlineAccounts = onlineAccountsMessage.getOnlineAccounts();
        LOGGER.trace(() -> String.format("Received %d online accounts from %s", peersOnlineAccounts.size(), peer));

        try (final Repository repository = RepositoryManager.getRepository()) {
            for (OnlineAccountData onlineAccountData : peersOnlineAccounts)
                this.verifyAndAddAccount(repository, onlineAccountData);
        } catch (DataException e) {
            LOGGER.error(String.format("Repository issue while verifying online accounts from peer %s", peer), e);
        }
    }

}
