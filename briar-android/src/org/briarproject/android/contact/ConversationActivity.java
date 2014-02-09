package org.briarproject.android.contact;

import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.LinearLayout.VERTICAL;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static org.briarproject.android.contact.ReadPrivateMessageActivity.RESULT_PREV_NEXT;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_MATCH;
import static org.briarproject.android.util.CommonLayoutParams.MATCH_WRAP_1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import org.briarproject.R;
import org.briarproject.android.BriarActivity;
import org.briarproject.android.util.HorizontalBorder;
import org.briarproject.android.util.ListLoadingProgressBar;
import org.briarproject.api.AuthorId;
import org.briarproject.api.ContactId;
import org.briarproject.api.android.DatabaseUiExecutor;
import org.briarproject.api.db.DatabaseComponent;
import org.briarproject.api.db.DbException;
import org.briarproject.api.db.MessageHeader;
import org.briarproject.api.db.NoSuchContactException;
import org.briarproject.api.db.NoSuchMessageException;
import org.briarproject.api.event.ContactRemovedEvent;
import org.briarproject.api.event.Event;
import org.briarproject.api.event.EventListener;
import org.briarproject.api.event.MessageAddedEvent;
import org.briarproject.api.event.MessageExpiredEvent;
import org.briarproject.api.lifecycle.LifecycleManager;
import org.briarproject.api.messaging.GroupId;
import org.briarproject.api.messaging.MessageId;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;

public class ConversationActivity extends BriarActivity
implements EventListener, OnClickListener, OnItemClickListener {

	private static final int REQUEST_READ = 2;
	private static final Logger LOG =
			Logger.getLogger(ConversationActivity.class.getName());

	private Map<MessageId, byte[]> bodyCache = new HashMap<MessageId, byte[]>();
	private String contactName = null;
	private ConversationAdapter adapter = null;
	private ListView list = null;
	private ListLoadingProgressBar loading = null;

	// Fields that are accessed from background threads must be volatile
	@Inject private volatile DatabaseComponent db;
	@Inject @DatabaseUiExecutor private volatile Executor dbUiExecutor;
	@Inject private volatile LifecycleManager lifecycleManager;
	private volatile ContactId contactId = null;
	private volatile GroupId groupId = null;
	private volatile AuthorId localAuthorId = null;

	@Override
	public void onCreate(Bundle state) {
		super.onCreate(state);

		Intent i = getIntent();
		int id = i.getIntExtra("briar.CONTACT_ID", -1);
		if(id == -1) throw new IllegalStateException();
		contactId = new ContactId(id);
		contactName = i.getStringExtra("briar.CONTACT_NAME");
		if(contactName == null) throw new IllegalStateException();
		setTitle(contactName);
		byte[] b = i.getByteArrayExtra("briar.GROUP_ID");
		if(b == null) throw new IllegalStateException();
		groupId = new GroupId(b);
		b = i.getByteArrayExtra("briar.LOCAL_AUTHOR_ID");
		if(b == null) throw new IllegalStateException();
		localAuthorId = new AuthorId(b);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(MATCH_MATCH);
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);

		adapter = new ConversationAdapter(this);
		list = new ListView(this);
		// Give me all the width and all the unused height
		list.setLayoutParams(MATCH_WRAP_1);
		list.setAdapter(adapter);
		list.setOnItemClickListener(this);
		layout.addView(list);

		// Show a progress bar while the list is loading
		list.setVisibility(GONE);
		loading = new ListLoadingProgressBar(this);
		layout.addView(loading);

		layout.addView(new HorizontalBorder(this));

		ImageButton composeButton = new ImageButton(this);
		composeButton.setBackgroundResource(0);
		composeButton.setImageResource(R.drawable.content_new_email);
		composeButton.setOnClickListener(this);
		layout.addView(composeButton);

		setContentView(layout);
	}

	@Override
	public void onResume() {
		super.onResume();
		db.addListener(this);
		loadHeaders();
	}

	private void loadHeaders() {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					Collection<MessageHeader> headers =
							db.getInboxMessageHeaders(contactId);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Loading headers took " + duration + " ms");
					displayHeaders(headers);
				} catch(NoSuchContactException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Contact removed");
					finishOnUiThread();
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayHeaders(final Collection<MessageHeader> headers) {
		runOnUiThread(new Runnable() {
			public void run() {
				list.setVisibility(VISIBLE);
				loading.setVisibility(GONE);
				adapter.clear();
				for(MessageHeader h : headers) {
					ConversationItem item = new ConversationItem(h);
					byte[] body = bodyCache.get(h.getId());
					if(body == null) loadMessageBody(h);
					else item.setBody(body);
					adapter.add(item);
				}
				adapter.sort(ConversationItemComparator.INSTANCE);
				adapter.notifyDataSetChanged();
				// Scroll to the bottom
				list.setSelection(adapter.getCount() - 1);
			}
		});
	}

	private void loadMessageBody(final MessageHeader h) {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					byte[] body = db.getMessageBody(h.getId());
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Loading message took " + duration + " ms");
					displayMessageBody(h.getId(), body);
				} catch(NoSuchMessageException e) {
					if(LOG.isLoggable(INFO)) LOG.info("Message expired");
					// The item will be removed when we get the event
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	private void displayMessageBody(final MessageId m, final byte[] body) {
		runOnUiThread(new Runnable() {
			public void run() {
				bodyCache.put(m, body);
				int count = adapter.getCount();
				for(int i = 0; i < count; i++) {
					ConversationItem item = adapter.getItem(i);
					if(item.getHeader().getId().equals(m)) {
						item.setBody(body);
						adapter.notifyDataSetChanged();
						// Scroll to the bottom
						list.setSelection(count - 1);
						return;
					}
				}
			}
		});
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);
		if(request == REQUEST_READ && result == RESULT_PREV_NEXT) {
			int position = data.getIntExtra("briar.POSITION", -1);
			if(position >= 0 && position < adapter.getCount())
				displayMessage(position);
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		db.removeListener(this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if(isFinishing()) markMessagesRead();
	}

	private void markMessagesRead() {
		List<MessageId> unread = new ArrayList<MessageId>();
		int count = adapter.getCount();
		for(int i = 0; i < count; i++) {
			MessageHeader h = adapter.getItem(i).getHeader();
			if(!h.isRead()) unread.add(h.getId());
		}
		if(unread.isEmpty()) return;
		if(LOG.isLoggable(INFO))
			LOG.info("Marking " + unread.size() + " messages read");
		markMessagesRead(Collections.unmodifiableList(unread));
	}

	private void markMessagesRead(final Collection<MessageId> unread) {
		dbUiExecutor.execute(new Runnable() {
			public void run() {
				try {
					lifecycleManager.waitForDatabase();
					long now = System.currentTimeMillis();
					for(MessageId m : unread) db.setReadFlag(m, true);
					long duration = System.currentTimeMillis() - now;
					if(LOG.isLoggable(INFO))
						LOG.info("Marking read took " + duration + " ms");
				} catch(DbException e) {
					if(LOG.isLoggable(WARNING))
						LOG.log(WARNING, e.toString(), e);
				} catch(InterruptedException e) {
					if(LOG.isLoggable(INFO))
						LOG.info("Interrupted while waiting for database");
					Thread.currentThread().interrupt();
				}
			}
		});
	}

	public void eventOccurred(Event e) {
		if(e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if(c.getContactId().equals(contactId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Contact removed");
				finishOnUiThread();
			}
		} else if(e instanceof MessageAddedEvent) {
			GroupId g = ((MessageAddedEvent) e).getGroup().getId();
			if(g.equals(groupId)) {
				if(LOG.isLoggable(INFO)) LOG.info("Message added, reloading");
				loadHeaders();
			}
		} else if(e instanceof MessageExpiredEvent) {
			if(LOG.isLoggable(INFO)) LOG.info("Message expired, reloading");
			loadHeaders();
		}
	}

	public void onClick(View view) {
		Intent i = new Intent(this, WritePrivateMessageActivity.class);
		i.putExtra("briar.CONTACT_NAME", contactName);
		i.putExtra("briar.GROUP_ID", groupId.getBytes());
		i.putExtra("briar.LOCAL_AUTHOR_ID", localAuthorId.getBytes());
		startActivity(i);
	}

	public void onItemClick(AdapterView<?> parent, View view, int position,
			long id) {
		displayMessage(position);
	}

	private void displayMessage(int position) {
		ConversationItem item = adapter.getItem(position);
		MessageHeader header = item.getHeader();
		Intent i = new Intent(this, ReadPrivateMessageActivity.class);
		i.putExtra("briar.CONTACT_ID", contactId.getInt());
		i.putExtra("briar.CONTACT_NAME", contactName);
		i.putExtra("briar.GROUP_ID", groupId.getBytes());
		i.putExtra("briar.LOCAL_AUTHOR_ID", localAuthorId.getBytes());
		i.putExtra("briar.AUTHOR_NAME", header.getAuthor().getName());
		i.putExtra("briar.MESSAGE_ID", header.getId().getBytes());
		i.putExtra("briar.CONTENT_TYPE", header.getContentType());
		i.putExtra("briar.TIMESTAMP", header.getTimestamp());
		i.putExtra("briar.POSITION", position);
		startActivityForResult(i, REQUEST_READ);
	}
}
