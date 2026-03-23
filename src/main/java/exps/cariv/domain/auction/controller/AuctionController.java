package exps.cariv.domain.auction.controller;

import exps.cariv.domain.auction.dto.response.AuctionDocumentResponse;
import exps.cariv.domain.auction.service.AuctionQueryService;
import exps.cariv.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vehicles/{vehicleId}/auction")
@Tag(name = "Auction", description = "경락사실확인서 조회 API")
public class AuctionController {

    private final AuctionQueryService queryService;

    @GetMapping
    @Operation(
            summary = "최신 경락사실확인서 조회",
            description = "차량의 최신 경락사실확인서 원본 정보와 OCR 결과/필드 이슈를 조회합니다."
    )
    public AuctionDocumentResponse getAuctionDocument(
            @AuthenticationPrincipal CustomUserDetails me,
            @PathVariable Long vehicleId
    ) {
        return queryService.getByVehicle(me.getCompanyId(), vehicleId);
    }
}
