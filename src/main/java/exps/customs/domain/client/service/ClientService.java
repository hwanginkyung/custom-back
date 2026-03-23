package exps.customs.domain.client.service;

import exps.customs.domain.client.dto.ClientResponse;
import exps.customs.domain.client.dto.CreateClientRequest;
import exps.customs.domain.client.dto.UpdateClientRequest;
import exps.customs.domain.client.entity.BrokerClient;
import exps.customs.domain.client.repository.BrokerClientRepository;
import exps.customs.global.exception.CustomException;
import exps.customs.global.exception.ErrorCode;
import exps.customs.global.tenant.TenantContext;
import exps.customs.global.tenant.aspect.TenantFiltered;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ClientService {

    private final BrokerClientRepository clientRepository;

    @TenantFiltered
    public List<ClientResponse> getAll() {
        Long companyId = TenantContext.getCompanyId();
        return clientRepository.findAllByCompanyIdOrderByCompanyNameAsc(companyId)
                .stream().map(ClientResponse::from).toList();
    }

    @TenantFiltered
    public List<ClientResponse> getActiveClients() {
        Long companyId = TenantContext.getCompanyId();
        return clientRepository.findAllByCompanyIdAndActiveTrue(companyId)
                .stream().map(ClientResponse::from).toList();
    }

    @TenantFiltered
    public ClientResponse getById(Long id) {
        BrokerClient client = clientRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND));
        return ClientResponse.from(client);
    }

    @Transactional
    public ClientResponse create(CreateClientRequest req) {
        BrokerClient client = BrokerClient.builder()
                .companyName(req.getCompanyName())
                .representativeName(req.getRepresentativeName())
                .businessNumber(req.getBusinessNumber())
                .phoneNumber(req.getPhoneNumber())
                .email(req.getEmail())
                .address(req.getAddress())
                .memo(req.getMemo())
                .active(true)
                .build();
        clientRepository.save(client);
        log.info("[Client] created id={}, name={}", client.getId(), client.getCompanyName());
        return ClientResponse.from(client);
    }

    @Transactional
    public ClientResponse update(Long id, UpdateClientRequest req) {
        BrokerClient client = clientRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND));

        if (req.getCompanyName() != null) client.setCompanyName(req.getCompanyName());
        if (req.getRepresentativeName() != null) client.setRepresentativeName(req.getRepresentativeName());
        if (req.getBusinessNumber() != null) client.setBusinessNumber(req.getBusinessNumber());
        if (req.getPhoneNumber() != null) client.setPhoneNumber(req.getPhoneNumber());
        if (req.getEmail() != null) client.setEmail(req.getEmail());
        if (req.getAddress() != null) client.setAddress(req.getAddress());
        if (req.getMemo() != null) client.setMemo(req.getMemo());

        clientRepository.save(client);
        log.info("[Client] updated id={}", id);
        return ClientResponse.from(client);
    }

    @Transactional
    public void toggleActive(Long id) {
        BrokerClient client = clientRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.CLIENT_NOT_FOUND));
        client.setActive(!client.isActive());
        clientRepository.save(client);
        log.info("[Client] toggled active id={}, active={}", id, client.isActive());
    }
}
