package org.thoughtcrime.securesms.jobs;

import com.difft.android.base.log.lumberjack.L;

public abstract class SendJob extends BaseJob {

    @SuppressWarnings("unused")
    private final static String TAG = L.INSTANCE.tag(SendJob.class);

    public SendJob(Parameters parameters) {
        super(parameters);
    }

    @Override
    public final void onRun() throws Exception {
    /*if (SignalStore.misc().isClientDeprecated()) {
      throw new TextSecureExpiredException(String.format("TextSecure expired (build %d, now %d)",
                                                         BuildConfig.BUILD_TIMESTAMP,
                                                         System.currentTimeMillis()));
    }*/

        L.i(() -> "Starting message send attempt");
        onSend();
        L.i(() -> "Message send completed");
    }

    protected abstract void onSend() throws Exception;

  /*protected static void markAttachmentsUploaded(long messageId, @NonNull OutgoingMediaMessage message) {
    List<Attachment> attachments = new LinkedList<>();

    attachments.addAll(message.getAttachments());
    attachments.addAll(Stream.of(message.getLinkPreviews()).map(lp -> lp.getThumbnail().orElse(null)).withoutNulls().toList());
    attachments.addAll(Stream.of(message.getSharedContacts()).map(Contact::getAvatarAttachment).withoutNulls().toList());

    if (message.getOutgoingQuote() != null) {
      attachments.addAll(message.getOutgoingQuote().getAttachments());
    }

    AttachmentDatabase database = SignalDatabase.attachments();

    for (Attachment attachment : attachments) {
      database.markAttachmentUploaded(messageId, attachment);
    }
  }

  protected String buildAttachmentString(@NonNull List<Attachment> attachments) {
    List<String> strings = attachments.stream().map(attachment -> {
      if (attachment instanceof DatabaseAttachment) {
        return ((DatabaseAttachment) attachment).getAttachmentId().toString();
      } else if (attachment.getUri() != null) {
        return attachment.getUri().toString();
      } else {
        return attachment.toString();
      }
    }).collect(Collectors.toList());

    return Util.join(strings, ", ");
  }*/
}
