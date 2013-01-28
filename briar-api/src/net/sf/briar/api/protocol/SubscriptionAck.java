package net.sf.briar.api.protocol;

/** A packet acknowledging a {@link SubscriptionUpdate}. */
public class SubscriptionAck {

	final long version;

	public SubscriptionAck(long version) {
		this.version = version;
	}

	/** Returns the version number of the acknowledged update. */
	public long getVersionNumber() {
		return version;
	}
}