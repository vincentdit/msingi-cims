package tz.co.msingi.cims.finance.reconciliation;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.exception.BusinessRuleException;
import tz.co.msingi.cims.finance.collection.PaymentReference;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationFile.Line;
import tz.co.msingi.cims.finance.reconciliation.ReconciliationFile.Parsed;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The day's file as GePG, collectors, banks and mobile money operators produce it. */
class ReconciliationFileTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Test
    void gepgFileIsReadStrictly() {
        Parsed p = ReconciliationFile.parse(ReconciliationFile.HEADER + "\nR1,B1,991234567890,P1,M-Pesa,\"50,000\",TZS,2026-11-05T09:10:00,MOBILE,255700000001\n", DAY);
        assertThat(p.statement()).isFalse();
        Line l = p.lines().get(0);
        assertThat(l.receipt()).isEqualTo("R1");
        assertThat(l.control()).isEqualTo("991234567890");
        assertThat(l.amount()).isEqualByComparingTo("50000");
        assertThatThrownBy(() -> ReconciliationFile.parse(ReconciliationFile.HEADER + "\nR1,B1,,,,10,TZS,yesterday,,\nR2,B1,,,,-5,TZS,,,", DAY))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2 line(s)");
    }

    @Test
    void mobileMoneyStatementTakesCompletedCreditsAndFindsTheReferenceInTheDetails() {
        String ref = PaymentReference.issue(12);
        String spaced = ref.substring(0, 4) + " " + ref.substring(4, 8) + " " + ref.substring(8);
        String csv = """
                M-PESA BUSINESS STATEMENT
                Organisation,Bahari College
                Receipt No.,Completion Time,Initiation Time,Details,Transaction Status,Paid In,Withdrawn,Balance
                SKA1B2C3,05-11-2026 09:14:22,05-11-2026 09:14:20,"Pay Bill from 255712345678 - JOHN DOE Acc. %s",Completed,"150,000.00",,"1,150,000.00"
                SKA1B2C4,05-11-2026 10:00:00,05-11-2026 10:00:00,Pay Bill from 255712345679 - JANE Acc. %s,Failed,"20,000.00",,
                SKA1B2C5,05-11-2026 11:00:00,05-11-2026 11:00:00,Business charge,Completed,,"1,000.00","1,149,000.00"
                SKA1B2C6,04-11-2026 16:00:00,04-11-2026 16:00:00,Pay Bill from 255712345670 Acc. %s,Completed,"30,000.00",,
                SKA1B2C7,05-11-2026 12:30:00,05-11-2026 12:30:00,Pay Bill from 255712345671 Acc. FEES,Completed,"5,000.00",,
                """.formatted(spaced, ref, ref);
        Parsed p = ReconciliationFile.parse(csv, DAY);
        assertThat(p.statement()).isTrue();
        assertThat(p.lines()).extracting(Line::receipt).containsExactly("SKA1B2C3", "SKA1B2C7");
        assertThat(p.skipped()).isEqualTo(3);
        Line l = p.lines().get(0);
        assertThat(l.control()).isEqualTo(ref);
        assertThat(l.amount()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(l.paidAt().toLocalDateTime().toString()).isEqualTo("2026-11-05T09:14:22");
        assertThat(p.lines().get(1).control()).isNull(); // no reference: an unknown bill for the Accountant to explain
    }

    @Test
    void bankStatementWithCreditAndDebitColumnsAndNoTransactionId() {
        String ref = PaymentReference.issue(12);
        String csv = """
                Posting Date;Value Date;Narration;Debit;Credit;Balance
                ;;Opening balance;;;2,000,000.00
                05/11/2026;05/11/2026;CASH DEP REF %s TUITION;;"450,000.00";2,450,000.00
                05/11/2026;05/11/2026;CHQ 00123 SUPPLIER;300,000.00;;2,150,000.00
                06/11/2026;06/11/2026;TRF %s;;10,000.00;2,160,000.00
                """.formatted(ref, ref);
        Parsed p = ReconciliationFile.parse(csv, DAY);
        assertThat(p.lines()).hasSize(1);
        Line l = p.lines().get(0);
        assertThat(l.control()).isEqualTo(ref);
        assertThat(l.amount()).isEqualByComparingTo("450000");
        assertThat(l.receipt()).startsWith("ST-");
        // the same statement imported again gives the same ids
        assertThat(ReconciliationFile.parse(csv, DAY).lines().get(0).receipt()).isEqualTo(l.receipt());
        assertThat(ReconciliationFile.parse(csv, DAY.plusDays(1)).lines().get(0).amount()).isEqualByComparingTo("10000");
        assertThatThrownBy(() -> ReconciliationFile.parse(csv, DAY.minusDays(1))).hasMessageContaining("dated 2026-11-04");
    }

    @Test
    void collectorFileWithAReferenceColumnStillWorks() {
        String ref = PaymentReference.issue(7);
        Parsed p = ReconciliationFile.parse("TransactionId,Reference,Amount,TransactionDate,Bank,MSISDN\nCRDB778812," + ref + ",80000,2026-11-05T09:15:00,CRDB Bank,\n", DAY);
        assertThat(p.statement()).isFalse();
        assertThat(p.lines().get(0).control()).isEqualTo(ref);
        assertThat(p.lines().get(0).psp()).isEqualTo("CRDB Bank");
    }

    @Test
    void amountsAndDatesInTheShapesBanksUse() {
        assertThat(ReconciliationFile.money("TZS 1,250,000.00")).isEqualByComparingTo("1250000");
        assertThat(ReconciliationFile.money("50,000.00 CR")).isEqualByComparingTo("50000");
        assertThat(ReconciliationFile.money("(1,000)")).isEqualByComparingTo("-1000");
        assertThat(ReconciliationFile.money("abc")).isNull();
        for (String d : new String[] {"05/11/2026", "5-Nov-2026", "05 Nov 2026 14:03", "2026-11-05 14:03:00", "05.11.2026", "05/11/2026 2:03 PM"}) {
            assertThat(ReconciliationFile.parseTime(d, DAY).toLocalDate()).as(d).isEqualTo(DAY);
        }
        assertThat(ReconciliationFile.findReference("no ref 812345678901 here")).isNull(); // fails the check digit
    }
}
