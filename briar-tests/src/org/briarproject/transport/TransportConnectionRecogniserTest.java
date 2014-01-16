package org.briarproject.transport;

import static org.briarproject.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;

import java.util.Random;

import org.briarproject.BriarTestCase;
import org.briarproject.TestUtils;
import org.briarproject.api.ContactId;
import org.briarproject.api.TransportId;
import org.briarproject.api.crypto.CryptoComponent;
import org.briarproject.api.crypto.SecretKey;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.transport.ConnectionContext;
import org.briarproject.api.transport.TemporarySecret;
import org.briarproject.util.ByteUtils;

import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.Test;

public class TransportConnectionRecogniserTest extends BriarTestCase {

	private final ContactId contactId = new ContactId(234);
	private final TransportId transportId =
			new TransportId(TestUtils.getRandomId());

	@Test
	public void testAddAndRemoveSecret() {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final byte[] secret = new byte[32];
		new Random().nextBytes(secret);
		final boolean alice = false;
		final SecretKey tagKey = context.mock(SecretKey.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		context.checking(new Expectations() {{
			// Add secret
			oneOf(crypto).deriveTagKey(secret, !alice);
			will(returnValue(tagKey));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(tagKey),
						with((long) i));
				will(new EncodeTagAction());
			}
			oneOf(tagKey).erase();
			// Remove secret
			oneOf(crypto).deriveTagKey(secret, !alice);
			will(returnValue(tagKey));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(tagKey),
						with((long) i));
				will(new EncodeTagAction());
			}
			oneOf(tagKey).erase();
		}});
		TemporarySecret s = new TemporarySecret(contactId, transportId, 123,
				alice, 0, secret, 0, 0, new byte[4]);
		TransportConnectionRecogniser recogniser =
				new TransportConnectionRecogniser(crypto, db, transportId);
		recogniser.addSecret(s);
		recogniser.removeSecret(contactId, 0);
		context.assertIsSatisfied();
	}

	@Test
	public void testAcceptConnection() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final byte[] secret = new byte[32];
		new Random().nextBytes(secret);
		final boolean alice = false;
		final SecretKey tagKey = context.mock(SecretKey.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		context.checking(new Expectations() {{
			// Add secret
			oneOf(crypto).deriveTagKey(secret, !alice);
			will(returnValue(tagKey));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(tagKey),
						with((long) i));
				will(new EncodeTagAction());
			}
			oneOf(tagKey).erase();
			// Accept connection 0
			oneOf(crypto).deriveTagKey(secret, !alice);
			will(returnValue(tagKey));
			// The window should slide to include connection 16
			oneOf(crypto).encodeTag(with(any(byte[].class)), with(tagKey),
					with(16L));
			will(new EncodeTagAction());
			// The updated window should be stored
			oneOf(db).setConnectionWindow(contactId, transportId, 0, 1,
					new byte[] {0, 1, 0, 0});
			oneOf(tagKey).erase();
			// Accept connection again - no expectations
		}});
		TemporarySecret s = new TemporarySecret(contactId, transportId, 123,
				alice, 0, secret, 0, 0, new byte[4]);
		TransportConnectionRecogniser recogniser =
				new TransportConnectionRecogniser(crypto, db, transportId);
		recogniser.addSecret(s);
		// Connection 0 should be expected
		byte[] tag = new byte[TAG_LENGTH];
		ConnectionContext ctx = recogniser.acceptConnection(tag);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertArrayEquals(secret, ctx.getSecret());
		assertEquals(0, ctx.getConnectionNumber());
		assertEquals(alice, ctx.getAlice());
		context.assertIsSatisfied();
	}

	private static class EncodeTagAction implements Action {

		public void describeTo(Description description) {
			description.appendText("Encodes a tag");
		}

		public Object invoke(Invocation invocation) throws Throwable {
			byte[] tag = (byte[]) invocation.getParameter(0);
			long connection = (Long) invocation.getParameter(2);
			// Encode a fake tag based on the connection number
			ByteUtils.writeUint32(connection, tag, 0);
			return null;
		}
	}
}