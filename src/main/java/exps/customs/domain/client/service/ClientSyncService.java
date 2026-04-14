package exps.customs.domain.client.service;

import exps.customs.domain.client.dto.ClientSyncPushItem;
import exps.customs.domain.client.dto.ClientSyncPushRequest;
import exps.customs.domain.client.dto.ClientSyncResponse;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.domain.ncustoms.service.NcustomsExportService;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ClientSyncService {

    private final BrokerClientRepository clientRepository;
    private final ObjectProvider<NcustomsExportService> ncustomsExportServiceProvider;

    @Value("${client.sync.push.agent-token:}")
    private String pushAgentToken;

    @Transactional
    public ClientSyncResponse syncFromNcustoms(Long companyId, String codePrefix, String keyword, Integer limit) {
        NcustomsExportService ncustoms = ncustomsExportServiceProvider.getIfAvailable();
        if (ncustoms == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "ncustoms datasource sync is disabled");
        }

        List<ClientSyncPushItem> items = ncustoms.getShippers(codePrefix, keyword, limit).stream()
                .map(ClientSyncPushItem::fromNcustoms)
                .toList();

        return upsert(companyId, items, "ncustoms-db", null);
    }

    @Transactional
    public ClientSyncResponse syncFromPush(ClientSyncPushRequest request, String providedToken) {
        validatePushToken(providedToken);

        String source = trimToNull(request.getSource());
        String sourceLabel = source == null ? "agent-push" : "agent-push:" + source;
        return upsert(request.getCompanyId(), request.getItems(), sourceLabel, trimToNull(request.getCheckpoint()));
    }

    private void validatePushToken(String providedToken) {
        String expected = trimToNull(pushAgentToken);
        if (expected == null) {
            throw new CustomException(ErrorCode.FORBIDDEN, "client sync push is not configured");
        }

        String provided = trimToNull(providedToken);
        if (provided == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "missing X-Agent-Token header");
        }

        boolean matched = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "invalid agent token");
        }
    }

    private ClientSyncResponse upsert(Long companyId, List<ClientSyncPushItem> items, String source, String checkpoint) {
        if (companyId == null || companyId <= 0L) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "companyId is required");
        }

        List<ClientSyncPushItem> safeItems = items == null ? List.of() : items;
        int received = safeItems.size();
        int skipped = 0;

        Map<String, ClientSyncPushItem> dedupedByCode = new LinkedHashMap<>();
        for (ClientSyncPushItem item : safeItems) {
            if (item == null) {
                skipped++;
                continue;
            }
            String code = trimToNull(item.getDealCode());
            if (code == null) {
                skipped++;
                continue;
            }
            dedupedByCode.put(code, item);
        }

        Map<String, BrokerClient> existingByCode = clientRepository.findAllByCompanyIdOrderByCompanyNameAsc(companyId).stream()
                .filter(client -> trimToNull(client.getExternalCode()) != null)
                .collect(Collectors.toMap(
                        client -> Objects.requireNonNull(trimToNull(client.getExternalCode())),
                        client -> client,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        int created = 0;
        int updated = 0;
        int unchanged = 0;

        for (Map.Entry<String, ClientSyncPushItem> entry : dedupedByCode.entrySet()) {
            String code = entry.getKey();
            ClientSyncPushItem item = entry.getValue();
            BrokerClient target = existingByCode.get(code);

            boolean isNew = target == null;
            if (isNew) {
                target = BrokerClient.builder().active(true).build();
                target.setCompanyId(companyId);
            }

            boolean changed = applySyncedFields(target, item, code);

            if (isNew) {
                clientRepository.save(target);
                existingByCode.put(code, target);
                created++;
                continue;
            }

            if (changed) {
                clientRepository.save(target);
                updated++;
            } else {
                unchanged++;
            }
        }

        log.info("[ClientSync] done companyId={}, source={}, received={}, distinct={}, created={}, updated={}, unchanged={}, skipped={}, checkpoint={}",
                companyId,
                source,
                received,
                dedupedByCode.size(),
                created,
                updated,
                unchanged,
                skipped,
                checkpoint);

        return ClientSyncResponse.builder()
                .received(received)
                .distinct(dedupedByCode.size())
                .created(created)
                .updated(updated)
                .unchanged(unchanged)
                .skipped(skipped)
                .source(source)
                .checkpoint(checkpoint)
                .build();
    }

    private boolean applySyncedFields(BrokerClient target, ClientSyncPushItem item, String code) {
        boolean changed = false;

        changed |= setString(target::getExternalCode, target::setExternalCode, code);

        String companyName = firstNonBlank(item.getDealSangho(), item.getDealName(), "DDeal-" + code);
        changed |= setString(target::getCompanyName, target::setCompanyName, companyName);

        changed |= setString(target::getRepresentativeName, target::setRepresentativeName, trimToNull(item.getDealName()));
        changed |= setString(target::getBusinessNumber, target::setBusinessNumber, trimToNull(item.getDealSaup()));
        changed |= setString(target::getCustomsUniqueCode, target::setCustomsUniqueCode, trimToNull(item.getDealTong()));
        changed |= setString(target::getIdentifierCode, target::setIdentifierCode, firstNonBlank(item.getDealSaupgbn(), item.getDealSaup()));
        changed |= setString(target::getPhoneNumber, target::setPhoneNumber, firstNonBlank(item.getDealTel(), item.getDealFax()));
        changed |= setString(target::getAddress, target::setAddress, joinAddress(item.getDealPost(), item.getDealJuso(), item.getDealJuso2()));
        changed |= setString(target::getMemo, target::setMemo, buildSyncMemo(item));

        if (!target.isActive()) {
            target.setActive(true);
            changed = true;
        }
        return changed;
    }

    private String buildSyncMemo(ClientSyncPushItem item) {
        List<String> parts = new ArrayList<>();
        appendPart(parts, "source", "ncustoms");
        appendPart(parts, "dealCode", item.getDealCode());
        appendPart(parts, "roadNmCd", item.getRoadNmCd());
        appendPart(parts, "buldMngNo", item.getBuldMngNo());
        appendPart(parts, "addDtTime", item.getAddDtTime());
        appendPart(parts, "editDtTime", item.getEditDtTime());
        return String.join(", ", parts);
    }

    private void appendPart(List<String> parts, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            parts.add(key + "=" + normalized);
        }
    }

    private String joinAddress(String post, String juso, String juso2) {
        String joined = String.join(" ",
                Objects.requireNonNullElse(trimToNull(post), ""),
                Objects.requireNonNullElse(trimToNull(juso), ""),
                Objects.requireNonNullElse(trimToNull(juso2), "")
        ).trim();
        return joined.isBlank() ? null : joined;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean setString(Supplier<String> getter, Consumer<String> setter, String next) {
        String current = trimToNull(getter.get());
        String normalizedNext = trimToNull(next);
        if (Objects.equals(current, normalizedNext)) {
            return false;
        }
        setter.accept(normalizedNext);
        return true;
    }
}

