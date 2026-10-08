package tz.co.msingi.cims.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public final class MessagingRepositories {

    private MessagingRepositories() {
    }

    public interface MessageRepository extends JpaRepository<Message, Long> {

        List<Message> findTop200ByStatusAndQueuedAtBeforeOrderByIdAsc(Message.Status status, OffsetDateTime before);

        Optional<Message> findByProviderRef(String providerRef);

        List<Message> findByCampaignIdOrderByIdAsc(Long campaignId);

        List<Message> findByCampaignIdAndStatus(Long campaignId, Message.Status status);

        @Query("""
                select m from Message m
                where (:status is null or m.status = :status) and (:kind is null or m.kind = :kind)
                  and (:phonePattern = '' or m.phone like :phonePattern)
                  and (:campaignId is null or m.campaignId = :campaignId)
                order by m.queuedAt desc, m.id desc""")
        Page<Message> search(Message.Status status, Message.Kind kind, String phonePattern, Long campaignId, Pageable pageable);

        /** SMS parts charged to the bundle (every accepted send) plus parts waiting to go (reserved). */
        @Query("""
                select coalesce(sum(m.chargedParts), 0)
                     + coalesce(sum(case when m.status = tz.co.msingi.cims.notification.Message.Status.QUEUED then m.parts else 0 end), 0)
                from Message m""")
        long usedParts();

        @Query("""
                select m.status, count(m), coalesce(sum(m.parts), 0) from Message m
                where m.queuedAt >= :from and m.queuedAt < :to group by m.status""")
        List<Object[]> statusSummary(OffsetDateTime from, OffsetDateTime to);

        @Query("""
                select m.kind, count(m), coalesce(sum(m.parts), 0) from Message m
                where m.queuedAt >= :from and m.queuedAt < :to and (m.sentAt is not null or m.status = tz.co.msingi.cims.notification.Message.Status.QUEUED)
                group by m.kind""")
        List<Object[]> kindSummary(OffsetDateTime from, OffsetDateTime to);

        @Query("select m.campaignId, m.status, count(m) from Message m where m.campaignId in :ids group by m.campaignId, m.status")
        List<Object[]> campaignStatus(java.util.Collection<Long> ids);
    }

    public interface MessagingSettingsRepository extends JpaRepository<MessagingSettings, Long> {
        Optional<MessagingSettings> findFirstBy();
    }

    public interface MessageTemplateRepository extends JpaRepository<MessageTemplate, Long> {
        Optional<MessageTemplate> findByCode(String code);
    }

    public interface MessageCampaignRepository extends JpaRepository<MessageCampaign, Long> {
        Page<MessageCampaign> findAllByOrderBySentAtDesc(Pageable pageable);
    }

    public interface SmsTopupRepository extends JpaRepository<SmsTopup, Long> {
        List<SmsTopup> findAllByOrderByRecordedAtDesc();

        @Query("select coalesce(sum(t.units), 0) from SmsTopup t")
        long totalUnits();
    }
}
