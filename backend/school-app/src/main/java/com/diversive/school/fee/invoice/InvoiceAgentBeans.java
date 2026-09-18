package com.diversive.school.fee.invoice;

import com.diversive.agent.spi.PreconditionCheck;
import com.diversive.school.platform.agent.SchoolScope;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Precondition checks about one invoice, shared by every fee capability that declares them. Each reads the
 * invoice through {@link InvoiceBalances}, inside the user's branch.
 *
 * <p>The four fee corrections (cancellation, credit, write-off, late fee waiver) are declared but not
 * implemented, so their other checks have no beans: preflight refuses them before any check runs.
 */
@Configuration(proxyBeanMethods = false)
class InvoiceAgentBeans {

    /** fee.payment.record names the money {@code amount_received}; fee.writeoff.propose names it {@code amount}. */
    private static final String[] AMOUNT_PARAMS = {"amount_received", "amount"};

    @Bean
    PreconditionCheck invoiceIsOpen(InvoiceBalances balances) {
        return PreconditionCheck.of("invoice_is_open", (params, user) ->
                balances.find(invoiceId(params), SchoolScope.branchId(user))
                        .map(InvoiceBalances.InvoiceBalance::isOpen)
                        .orElse(false));
    }

    @Bean
    PreconditionCheck amountWithinBalance(InvoiceBalances balances) {
        return PreconditionCheck.of("amount_within_balance", (params, user) ->
                balances.find(invoiceId(params), SchoolScope.branchId(user))
                        .map(balance -> amount(params).compareTo(balance.outstanding()) <= 0)
                        .orElse(false));
    }

    private static long invoiceId(Map<String, Object> params) {
        return (Long) params.get("invoice_id");
    }

    private static BigDecimal amount(Map<String, Object> params) {
        for (String name : AMOUNT_PARAMS) {
            if (params.get(name) instanceof BigDecimal amount) {
                return amount;
            }
        }
        throw new IllegalStateException("amount_within_balance needs one of " + String.join(", ", AMOUNT_PARAMS));
    }
}
