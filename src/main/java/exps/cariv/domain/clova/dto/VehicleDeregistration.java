package exps.cariv.domain.clova.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 자동차말소등록사실증명서에서 추출한 구조화 데이터
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VehicleDeregistration {

    // === 차량 기본 정보 ===
    private String vehicleNo;              // 자동차등록번호
    private String carType;                // 차종 (승용/승합/화물 등)
    private String mileage;                // 주행거리 (km)
    private String modelName;              // 차명
    private String vin;                    // 차대번호
    private String engineType;             // 원동기형식
    private Integer modelYear;             // 모델연도
    private String vehicleUse;             // 용도 (자가용/사업용)
    private String specManagementNo;       // 제원관리번호
    private String firstRegistratedAt;     // 최초등록일

    // === 소유자 ===
    private String ownerName;              // 소유자 성명(명칭)
    private String ownerId;                // 생년월일 또는 법인등록번호

    // === 말소 정보 ===
    private String deregistrationDate;     // 말소등록일
    private String deregistrationReason;   // 말소등록구분 (수출말소, 직권말소, 해체말소 등)
    private String certificateUse;         // 증명서 용도 (증명용 등)

    // === 권리관계 ===
    private String seizureCount;           // 압류 건수
    private String mortgageCount;          // 저당권 건수
    private String businessUsePeriod;      // 사업용 사용기간

    // === 발행 정보 ===
    private String issueDate;              // 발행 연월일
    private String issuer;                 // 발행 기관

    // === 품질 ===
    private Double qualityScore;
    private Boolean qualityGatePassed;
    private Boolean needsRetry;
    private String qualityReason;
    private String documentType;
    private Double documentTypeScore;
}
