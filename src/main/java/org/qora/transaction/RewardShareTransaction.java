package org.qora.transaction;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.qora.account.Account;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.block.BlockChain;
import org.qora.crypto.Crypto;
import org.qora.data.account.RewardShareData;
import org.qora.data.transaction.RewardShareTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.transform.Transformer;

public class RewardShareTransaction extends Transaction {

	// Properties
	private RewardShareTransactionData rewardShareTransactionData;

	// Constructors

	public RewardShareTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.rewardShareTransactionData = (RewardShareTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getMintingAccount().getAddress()))
			return true;

		if (address.equals(this.getRecipient().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getMintingAccount().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	private RewardShareData getExistingRewardShare() throws DataException {
		// Look up any existing reward-share (using transaction's reward-share public key)
		RewardShareData existingRewardShareData = this.repository.getAccountRepository().getRewardShare(this.rewardShareTransactionData.getRewardSharePublicKey());
		if (existingRewardShareData == null)
			// No luck, try looking up existing reward-share using minting & recipient account info
			existingRewardShareData = this.repository.getAccountRepository().getRewardShare(this.rewardShareTransactionData.getMinterPublicKey(), this.rewardShareTransactionData.getRecipient());

		return existingRewardShareData;
	}

	private boolean doesRewardShareMatch(RewardShareData rewardShareData) {
		return rewardShareData.getRecipient().equals(this.rewardShareTransactionData.getRecipient())
				&& Arrays.equals(rewardShareData.getMinterPublicKey(), this.rewardShareTransactionData.getMinterPublicKey())
				&& Arrays.equals(rewardShareData.getRewardSharePublicKey(), this.rewardShareTransactionData.getRewardSharePublicKey());
	}

	// Navigation

	public PublicKeyAccount getMintingAccount() {
		return new PublicKeyAccount(this.repository, this.rewardShareTransactionData.getMinterPublicKey());
	}

	public Account getRecipient() {
		return new Account(this.repository, this.rewardShareTransactionData.getRecipient());
	}

	// Processing

	private static final BigDecimal MAX_SHARE = BigDecimal.valueOf(100).setScale(2);

	@Override
	public ValidationResult isFeeValid() throws DataException {
		// Look up any existing reward-share (using transaction's reward-share public key)
		RewardShareData existingRewardShareData = this.getExistingRewardShare();
		// If we have an existing reward-share then minter/recipient/reward-share-public-key should all match.
		// This is to prevent malicious actors using multiple (fake) reward-share public keys for the same minter/recipient combo,
		// or reusing the same reward-share public key for a different minter/recipient pair.
		if (existingRewardShareData != null && !this.doesRewardShareMatch(existingRewardShareData))
			return ValidationResult.INVALID_PUBLIC_KEY;

		final boolean isRecipientAlsoMinter = getCreator().getAddress().equals(this.rewardShareTransactionData.getRecipient());

		// Fee can be zero if setting up new self-share
		if (isRecipientAlsoMinter && existingRewardShareData == null && this.transactionData.getFee().compareTo(BigDecimal.ZERO) >= 0)
			return ValidationResult.OK;

		return super.isFeeValid();
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check reward share given to recipient
		if (this.rewardShareTransactionData.getSharePercent().compareTo(BigDecimal.ZERO) < 0
				|| this.rewardShareTransactionData.getSharePercent().compareTo(MAX_SHARE) > 0)
			return ValidationResult.INVALID_REWARD_SHARE_PERCENT;

		PublicKeyAccount creator = getCreator();

		// Check reward-share public key is correct length
		if (this.rewardShareTransactionData.getRewardSharePublicKey().length != Transformer.PUBLIC_KEY_LENGTH)
			return ValidationResult.INVALID_PUBLIC_KEY;

		Account recipient = getRecipient();
		if (!Crypto.isValidAddress(recipient.getAddress()))
			return ValidationResult.INVALID_ADDRESS;

		// Creator themselves needs to be allowed to mint
		if (!creator.canMint())
			return ValidationResult.NOT_MINTING_ACCOUNT;

		// Qortal: special rules in play depending whether recipient is also minter
		final boolean isRecipientAlsoMinter = creator.getAddress().equals(recipient.getAddress());
		if (!isRecipientAlsoMinter && !creator.canRewardShare())
			return ValidationResult.ACCOUNT_CANNOT_REWARD_SHARE;

		// Look up any existing reward-share (using transaction's reward-share public key)
		RewardShareData existingRewardShareData = this.getExistingRewardShare();
		// If we have an existing reward-share then minter/recipient/reward-share-public-key should all match.
		// This is to prevent malicious actors using multiple (fake) reward-share public keys for the same minter/recipient combo,
		// or reusing the same reward-share public key for a different minter/recipient pair.
		if (existingRewardShareData != null && !this.doesRewardShareMatch(existingRewardShareData))
			return ValidationResult.INVALID_PUBLIC_KEY;

		final boolean isSharePercentZero = this.rewardShareTransactionData.getSharePercent().compareTo(BigDecimal.ZERO) == 0;

		if (existingRewardShareData == null) {
			// This is a new reward-share

			// No point starting a new reward-share with 0% share (i.e. delete reward-share)
			if (isSharePercentZero)
				return ValidationResult.INVALID_REWARD_SHARE_PERCENT;

			// Check the minting account hasn't reach maximum number of reward-shares
			int rewardShareCount = this.repository.getAccountRepository().countRewardShares(creator.getPublicKey());
			if (rewardShareCount >= BlockChain.getInstance().getMaxRewardSharesPerMintingAccount())
				return ValidationResult.MAXIMUM_REWARD_SHARES;
		} else {
			// This transaction intends to modify/terminate an existing reward-share

			// Modifying an existing self-share is pointless and forbidden (due to 0 fee). Deleting self-share is OK though.
			if (isRecipientAlsoMinter && !isSharePercentZero)
				return ValidationResult.INVALID_REWARD_SHARE_PERCENT;
		}

		// Fee checking needed if not setting up new self-share
		if (!(isRecipientAlsoMinter && existingRewardShareData == null))
			// Check creator has enough funds
			if (creator.getConfirmedBalance(Asset.QORT).compareTo(rewardShareTransactionData.getFee()) < 0)
				return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		PublicKeyAccount mintingAccount = getMintingAccount();

		// Grab any previous share info for orphaning purposes
		RewardShareData rewardShareData = this.repository.getAccountRepository().getRewardShare(mintingAccount.getPublicKey(),
				rewardShareTransactionData.getRecipient());

		if (rewardShareData != null)
			rewardShareTransactionData.setPreviousSharePercent(rewardShareData.getSharePercent());

		// Save this transaction, with previous share info
		this.repository.getTransactionRepository().save(rewardShareTransactionData);

		// 0% share is actually a request to delete existing reward-share
		if (rewardShareTransactionData.getSharePercent().compareTo(BigDecimal.ZERO) == 0) {
			this.repository.getAccountRepository().delete(mintingAccount.getPublicKey(), rewardShareTransactionData.getRecipient());
		} else {
			// Save reward-share info
			rewardShareData = new RewardShareData(mintingAccount.getPublicKey(), rewardShareTransactionData.getRecipient(), rewardShareTransactionData.getRewardSharePublicKey(), rewardShareTransactionData.getSharePercent());
			this.repository.getAccountRepository().save(rewardShareData);
		}
	}

	@Override
	public void processReferencesAndFees() throws DataException {
		super.processReferencesAndFees();

		// If reward-share recipient has no last-reference then use this transaction's signature as last-reference so they can spend their block rewards
		Account recipient = new Account(this.repository, rewardShareTransactionData.getRecipient());
		if (recipient.getLastReference() == null)
			recipient.setLastReference(rewardShareTransactionData.getSignature());
	}

	@Override
	public void orphan() throws DataException {
		// Revert
		PublicKeyAccount mintingAccount = getMintingAccount();

		if (rewardShareTransactionData.getPreviousSharePercent() != null) {
			// Revert previous sharing arrangement
			RewardShareData rewardShareData = new RewardShareData(mintingAccount.getPublicKey(), rewardShareTransactionData.getRecipient(),
					rewardShareTransactionData.getRewardSharePublicKey(), rewardShareTransactionData.getPreviousSharePercent());

			this.repository.getAccountRepository().save(rewardShareData);
		} else {
			// No previous arrangement so simply delete
			this.repository.getAccountRepository().delete(mintingAccount.getPublicKey(), rewardShareTransactionData.getRecipient());
		}

		// Save this transaction, with removed previous share info
		rewardShareTransactionData.setPreviousSharePercent(null);
		this.repository.getTransactionRepository().save(rewardShareTransactionData);
	}

	@Override
	public void orphanReferencesAndFees() throws DataException {
		super.orphanReferencesAndFees();

		// If recipient didn't have a last-reference prior to this transaction then remove it
		Account recipient = new Account(this.repository, rewardShareTransactionData.getRecipient());
		if (Arrays.equals(recipient.getLastReference(), rewardShareTransactionData.getSignature()))
			recipient.setLastReference(null);
	}

}
