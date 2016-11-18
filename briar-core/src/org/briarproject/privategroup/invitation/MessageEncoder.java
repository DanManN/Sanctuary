package org.briarproject.privategroup.invitation;

import org.briarproject.api.data.BdfDictionary;
import org.briarproject.api.identity.Author;
import org.briarproject.api.nullsafety.NotNullByDefault;
import org.briarproject.api.sync.GroupId;
import org.briarproject.api.sync.Message;
import org.briarproject.api.sync.MessageId;

import javax.annotation.Nullable;

@NotNullByDefault
interface MessageEncoder {

	BdfDictionary encodeMetadata(MessageType type, GroupId privateGroupId,
			long timestamp, boolean local, boolean read, boolean visible,
			boolean available);

	void setVisibleInUi(BdfDictionary meta, boolean visible);

	void setAvailableToAnswer(BdfDictionary meta, boolean available);

	Message encodeInviteMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, String groupName, Author creator, byte[] salt,
			@Nullable String message, byte[] signature);

	Message encodeJoinMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId);

	Message encodeLeaveMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp, @Nullable MessageId previousMessageId);

	Message encodeAbortMessage(GroupId contactGroupId, GroupId privateGroupId,
			long timestamp);
}