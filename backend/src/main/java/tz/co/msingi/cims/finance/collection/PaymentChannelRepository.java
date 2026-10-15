package tz.co.msingi.cims.finance.collection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentChannelRepository extends JpaRepository<PaymentChannel, Long> {

    List<PaymentChannel> findAllByOrderBySortOrderAscIdAsc();

    List<PaymentChannel> findByActiveTrueOrderBySortOrderAscIdAsc();

    boolean existsByProviderAndAccountNumberIgnoreCase(PaymentProvider provider, String accountNumber);
}
