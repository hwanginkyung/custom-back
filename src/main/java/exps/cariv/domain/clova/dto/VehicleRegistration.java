package exps.cariv.domain.clova.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * 자동차등록증에서 추출한 구조화 데이터
 * RegistrationDocument 엔티티와 필드 매핑
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VehicleRegistration {

    // === Vehicle과 컬럼명 통일 ===
    private String vin;                    // 차대번호
    private String vehicleNo;             // 자동차등록번호
    private String carType;               // 차종 (대형/중형/소형 승용 등)
    private String vehicleUse;            // 용도 (자가용/사업용)
    private String modelName;             // 차명
    private String engineType;            // 원동기형식
    private String ownerName;             // 소유자 성명
    private String ownerId;              // 생년월일 또는 법인등록번호
    private Integer modelYear;            // 모델연도 (제작연월에서 추출)
    private String fuelType;              // 연료 종류
    private String manufactureYearMonth;  // 형식 및 제작연월 (yyyy-MM, 7자)
    private Integer displacement;         // 배기량 (cc, 숫자만)
    private String firstRegistratedAt;    // 최초등록일 (yyyy-MM-dd)

    // === 등록증에만 있는 값 ===
    private String address;               // 사용본거지
    private String modelCode;             // 형식코드

    // 제원
    private String lengthVal;             // 길이
    private String widthVal;              // 너비
    private String heightVal;             // 높이
    private String weight;                // 총중량
    private String seating;               // 승차정원
    private String maxLoad;               // 최대적재량
    private String power;                 // 정격출력

    // === 운영 추적 ===
    private Boolean qualityGatePassed;    // 품질 게이트 통과 여부
    private Boolean needsRetry;           // 재촬영/보정 필요 여부
    private Double qualityScore;          // 0~1
    private String qualityReason;         // 게이트 실패/주의 사유
    private String documentType;          // 문서 타입 분류 결과
    private Double documentTypeScore;     // 문서 타입 분류 신뢰도

    // 필드별 값 + confidence + evidence(anchor/value bbox)
    private Map<String, FieldEvidence> evidence;
}
