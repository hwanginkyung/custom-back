package exps.cariv.domain.malso.print;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MalsoXlsxGeneratorTest {

    private final MalsoXlsxGenerator generator = new MalsoXlsxGenerator();

    @Test
    void keepsTemplatePrintAreaForA4() throws Exception {
        MalsoXlsxData data = new MalsoXlsxData(
                "홍길동",
                "123456-1234567",
                "서울시 강남구",
                "12가3456",
                "KMH12345678901234",
                12345L,
                "",
                "",
                "CARIV EXPORT",
                "대표자",
                "123-45-67890",
                "서울시 서초구",
                null
        );

        byte[] xlsx = generator.generate(data);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            int idx = wb.getSheetIndex("신청서");
            if (idx < 0) {
                idx = 0;
            }
            String printArea = wb.getPrintArea(idx);

            assertNotNull(printArea);
            // 템플릿 기준 A1:K37 영역을 유지해야 한다.
            assertTrue(printArea.contains("$A$1"));
            assertTrue(printArea.contains("$K$37"));
            // 잘못된 동적 계산(IW 등)으로 영역이 커지면 출력이 좌상단에 축소된다.
            assertFalse(printArea.contains("$IW$"));
        }
    }
}
