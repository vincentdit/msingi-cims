package tz.co.msingi.cims.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import tz.co.msingi.cims.common.BaseEntity;

import java.math.BigDecimal;

/** An institution's messaging settings: language of event messages, sender name, price per SMS part, low-balance warning. */
@Getter
@Setter
@Entity
@Table(name = "messaging_settings")
public class MessagingSettings extends BaseEntity {
    @Column(nullable = false)
    private String defaultLanguage = "EN";
    private String senderId;
    @Column(nullable = false)
    private BigDecimal pricePerSms = new BigDecimal("25");
    @Column(nullable = false)
    private int lowBalanceWarning = 500;
}
