package tz.co.msingi.cims.integration.log;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import tz.co.msingi.cims.finance.gepg.GepgGateway;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder.Outcome;
import tz.co.msingi.cims.integration.log.IntegrationCallRecorder.ExternalSystem;
import tz.co.msingi.cims.integration.nactvet.NactvetAwardClient;
import tz.co.msingi.cims.integration.nactvet.NactvetGateway;
import tz.co.msingi.cims.integration.necta.NectaClient;
import tz.co.msingi.cims.notification.SmsGateway;

import java.util.Collection;
import java.util.Optional;

/**
 * Wraps the ports to outside systems so every call is logged with its outcome and duration (integration health, and the
 * auditor's integration log). Works the same for the live and the simulated adapters; the mode is taken from the adapter.
 * Only references are logged (an index number, a bill id, a masked phone number), never what was sent or received.
 */
@Component
public class IntegrationLoggingPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<IntegrationCallRecorder> recorder;

    public IntegrationLoggingPostProcessor(ObjectProvider<IntegrationCallRecorder> recorder) {
        this.recorder = recorder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        if (NectaClient.class.isAssignableFrom(type)) return wrap(bean, NectaClient.class, ExternalSystem.NECTA);
        if (NactvetGateway.class.isAssignableFrom(type)) return wrap(bean, NactvetGateway.class, ExternalSystem.NACTVET);
        if (NactvetAwardClient.class.isAssignableFrom(type)) return wrap(bean, NactvetAwardClient.class, ExternalSystem.NACTVET_AWARDS);
        if (GepgGateway.class.isAssignableFrom(type)) return wrap(bean, GepgGateway.class, ExternalSystem.GEPG);
        if (SmsGateway.class.isAssignableFrom(type)) return wrap(bean, SmsGateway.class, ExternalSystem.SMS);
        return bean;
    }

    private Object wrap(Object bean, Class<?> port, ExternalSystem system) {
        boolean live = !AopUtils.getTargetClass(bean).getSimpleName().startsWith("Simulated");
        ProxyFactory pf = new ProxyFactory(bean);
        pf.addInterface(port);
        pf.addAdvice((MethodInterceptor) inv -> {
            if (inv.getMethod().getDeclaringClass() == Object.class) return inv.proceed();
            String op = inv.getMethod().getName();
            String ref = reference(system, op, inv.getArguments());
            long start = System.nanoTime();
            try {
                Object result = inv.proceed();
                long ms = (System.nanoTime() - start) / 1_000_000;
                String failure = failure(result);
                recorder.getObject().record(system, op, false, live, failure == null ? Outcome.SUCCESS : Outcome.FAILURE, ms, ref,
                        failure != null ? failure : detail(result));
                return result;
            } catch (Throwable t) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                recorder.getObject().record(system, op, false, live, Outcome.FAILURE, ms, ref,
                        t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                throw t;
            }
        });
        return pf.getProxy();
    }

    /** A call that returned but was refused by the other side. */
    private static String failure(Object result) {
        if (result instanceof SmsGateway.Result r && !r.accepted()) return r.error() == null ? "Not accepted by the SMS gateway" : r.error();
        if (result instanceof GepgGateway.ControlNumberResponse r && !r.issued() && r.errorMessage() != null) return r.errorMessage();
        return null;
    }

    private static String detail(Object result) {
        if (result instanceof Optional<?> o && o.isEmpty()) return "No record found";
        if (result instanceof GepgGateway.ControlNumberResponse r && !r.issued()) return "Control number pending";
        if (result instanceof Collection<?> c) return c.size() + (c.size() == 1 ? " result" : " results");
        if (result instanceof Boolean b) return b ? "Confirmed" : "Not confirmed";
        return null;
    }

    private static String reference(ExternalSystem system, String op, Object[] args) {
        try {
            return switch (system) {
                case NECTA -> args.length >= 3 ? args[0] + " " + args[1] + " " + args[2] : null;
                case NACTVET_AWARDS -> args.length >= 2 ? args[0] + " " + args[1] : null;
                case NACTVET -> switch (op) {
                    case "submit" -> args.length >= 2 && args[1] instanceof Collection<?> c ? c.size() + " candidates" : null;
                    case "fetchOutcomes" -> args.length >= 1 ? "batch " + args[0] : null;
                    case "confirmAdmission" -> args.length >= 2 ? String.valueOf(args[1]) : null;
                    default -> null;
                };
                case GEPG -> args.length >= 1 && args[0] instanceof GepgGateway.BillRequest b ? "bill " + b.billId() : null;
                case SMS -> args.length >= 1 ? mask(String.valueOf(args[0])) + (args.length >= 4 && args[3] != null ? " · " + args[3] : "") : null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 255712345678 → 255712***678: enough to trace a message, not to read the number off a screen. */
    static String mask(String phone) {
        String p = phone.replaceAll("\\s+", "");
        if (p.length() < 7) return "***";
        return p.substring(0, p.length() - 6) + "***" + p.substring(p.length() - 3);
    }
}
