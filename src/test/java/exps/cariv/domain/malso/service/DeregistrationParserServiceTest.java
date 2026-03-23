package exps.cariv.domain.malso.service;

import exps.cariv.domain.malso.dto.DeregParseResult;
import exps.cariv.domain.upstage.dto.Content;
import exps.cariv.domain.upstage.dto.UpstageElement;
import exps.cariv.domain.upstage.dto.UpstageResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeregistrationParserServiceTest {

    private final DeregistrationParserService parser = new DeregistrationParserService();

    @Test
    void parsesExportDeregistrationFormWithCurrentLayoutLabels() {
        String html = """
                <table>
                  <tr><td>자동차등록번호</td><td>68마3644</td><td>차대번호</td><td>KNAJG55139K000824</td></tr>
                  <tr><td>차명</td><td>쏘울</td><td>모델연도</td><td>2009</td></tr>
                  <tr><td>제원관리번호</td><td>A01-1-00043-0002-1208</td><td>용도</td><td>자가용</td></tr>
                  <tr><td>성명 (명칭)</td><td>현대글로비스(주)(상품용)</td><td>생년월일 (법인등록번호)</td><td>110111-2177388</td></tr>
                  <tr><td>말소등록일</td><td>2025-12-01</td><td>증명서 용도</td><td>증명용</td></tr>
                  <tr><td>말소등록 구분</td><td>수출예정(수출말소)</td><td>권리관계 여부</td><td>압류: 0건 저당권: 0건</td></tr>
                </table>
                """;

        UpstageResponse response = new UpstageResponse(List.of(
                table(html),
                paragraph("제 2824-202512-000866 호 (No.) 문서확인번호 7202191503906678")
        ));

        DeregParseResult result = parser.parseAndValidate(response);

        assertEquals("68마3644", result.parsed().registrationNo());
        assertEquals("KNAJG55139K000824", result.parsed().vin());
        assertEquals("쏘울", result.parsed().modelName());
        assertEquals(2009, result.parsed().modelYear());
        assertEquals("현대글로비스(주)(상품용)", result.parsed().ownerName());
        assertEquals("110111-2177388", result.parsed().ownerId());
        assertEquals(LocalDate.of(2025, 12, 1), result.parsed().deRegistrationDate());
        assertEquals("수출예정(수출말소)", result.parsed().deRegistrationReason());
        assertEquals("A01-1-00043-0002-1208", result.parsed().specNo());
        assertEquals("2824-202512-000866", result.parsed().documentNo());
        assertTrue(result.missingFields().isEmpty());
        assertTrue(result.errorFields().isEmpty());
    }

    @Test
    void parsesScrapDeregistrationReasonAndOwnerBirthDate() {
        String html = """
                <table>
                  <tr><td>자동차등록번호</td><td>26거1111</td><td>차대번호</td><td>KNALL411BBA053766</td></tr>
                  <tr><td>차명</td><td>K7</td><td>모델연도</td><td>2011</td></tr>
                  <tr><td>제원관리번호</td><td>A01-1-00045-0007-1310</td><td>용도</td><td>자가용</td></tr>
                  <tr><td>성명 (명칭)</td><td>김화인</td><td>생년월일 (법인등록번호)</td><td>1989-06-24</td></tr>
                  <tr><td>말소등록일</td><td>2025-11-10</td><td>증명서 용도</td><td>증명용</td></tr>
                  <tr><td>말소등록 구분</td><td>폐차(자진말소)</td><td>권리관계 여부</td><td>압류: 0건 저당권: 0건</td></tr>
                </table>
                """;

        UpstageResponse response = new UpstageResponse(List.of(
                table(html),
                paragraph("제 4123-202511-029242 호")
        ));

        DeregParseResult result = parser.parseAndValidate(response);

        assertEquals("26거1111", result.parsed().registrationNo());
        assertEquals(LocalDate.of(2025, 11, 10), result.parsed().deRegistrationDate());
        assertEquals("폐차(자진말소)", result.parsed().deRegistrationReason());
        assertEquals("1989-06-24", result.parsed().ownerId());
        assertEquals("A01-1-00045-0007-1310", result.parsed().specNo());
        assertEquals("4123-202511-029242", result.parsed().documentNo());
        assertTrue(result.missingFields().isEmpty());
    }

    @Test
    void extractsRequiredFieldsFromNonTableTextFallback() {
        String text = """
                자동차말소등록사실증명서 제 4123-202511-027842 호
                자동차등록번호 57노5500 차대번호 KNAFK412BFA924680
                모델연도 2015 제원관리번호 A01-1-00053-0075-1214
                성명 김미림 생년월일 1979-08-07
                말소등록일 2025-11-10 말소등록 구분 폐차(자진말소)
                """;

        UpstageResponse response = new UpstageResponse(List.of(
                paragraph(text)
        ));

        DeregParseResult result = parser.parseAndValidate(response);

        assertEquals("57노5500", result.parsed().registrationNo());
        assertEquals(LocalDate.of(2025, 11, 10), result.parsed().deRegistrationDate());
        assertEquals("폐차(자진말소)", result.parsed().deRegistrationReason());
        assertEquals("A01-1-00053-0075-1214", result.parsed().specNo());
        assertEquals("4123-202511-027842", result.parsed().documentNo());
        assertEquals(2015, result.parsed().modelYear());
        assertEquals("1979-08-07", result.parsed().ownerId());
        assertTrue(result.missingFields().isEmpty());
        assertTrue(result.errorFields().isEmpty());
    }

    private UpstageElement table(String html) {
        return new UpstageElement("table", new Content(html, null, null), 1, 1);
    }

    private UpstageElement paragraph(String text) {
        return new UpstageElement("paragraph", new Content(null, null, text), 2, 1);
    }
}
