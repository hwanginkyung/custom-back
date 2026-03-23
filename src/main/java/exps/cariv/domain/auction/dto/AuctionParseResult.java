package exps.cariv.domain.auction.dto;

import java.util.List;

public record AuctionParseResult(
        AuctionParsed parsed,
        List<String> missingFields,
        List<String> errorFields
) {}
