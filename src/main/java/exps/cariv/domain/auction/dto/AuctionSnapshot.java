package exps.cariv.domain.auction.dto;

import java.time.LocalDate;

public record AuctionSnapshot(
        String registrationNo,
        String chassisNo,
        String model,
        Integer modelYear,
        Long mileage,
        Integer displacement,
        LocalDate initialRegistrationDate,
        String fuel,
        String color
) {}
