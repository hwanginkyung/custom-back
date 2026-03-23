package exps.cariv.domain.malso.print;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 말소등록신청서 PDF 직접 생성기.
 * <p>
 * PDFBox로 A4 용지에 구분선(격자)을 포함해 직접 그립니다.
 * LibreOffice 변환 없이 구분선 손실·크기 축소 문제를 해결합니다.
 * <p>
 * 한국어(CJK) 문자: classpath TTF 폰트 (NanumGothic 등)
 * 영문/숫자/특수문자: Helvetica 내장 폰트
 * → 혼합 렌더링으로 모든 문자 정상 출력 보장
 */
@Component
@Slf4j
public class MalsoPdfGenerator {

    /** 한국어 폰트 후보 경로 (classpath) */
    private static final String[] FONT_PATHS = {
            "fonts/malgun.ttf",
            "fonts/NanumGothic.ttf",
            "fonts/NanumGothicBold.ttf",
    };

    /** 시스템 한국어 폰트 후보 경로 (EC2 Amazon Linux / Ubuntu / Windows) */
    private static final String[] SYSTEM_FONT_PATHS = {
            // Amazon Linux 2 / RHEL (yum install google-noto-sans-cjk-ttc-fonts / nanum-gothic-fonts)
            "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/naver-nanum/NanumGothic.ttf",
            "/usr/share/fonts/nanum-gothic/NanumGothic.ttf",
            // Ubuntu / Debian (apt install fonts-nanum)
            "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
            "/usr/share/fonts/truetype/malgun/malgun.ttf",
            "/usr/share/fonts/truetype/malgun/Malgun.ttf",
            // 수동 설치 경로
            "/usr/local/share/fonts/malgun/malgun.ttf",
            "/usr/local/share/fonts/NanumGothic.ttf",
            "/usr/share/fonts-droid-fallback/truetype/DroidSansFallback.ttf",
            // Windows
            "C:/Windows/Fonts/malgun.ttf",
    };

    // A4 치수 (pt) — A4 전체를 채우도록 마진 조정
    private static final float PW = PDRectangle.A4.getWidth();   // 595.28
    private static final float PH = PDRectangle.A4.getHeight();  // 841.89
    private static final float ML = 36f, MR = 36f, MT = 28f, MB = 28f;
    private static final float CW = PW - ML - MR;               // 523.28
    private static final float THIN = 0.5f;
    private static final float THICK = 1.2f;

    private float y(float topOffset) {
        return PH - MT - topOffset;
    }

    /**
     * PDF 바이트 생성.
     */
    public byte[] generate(MalsoXlsxData data) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font krFont = loadKoreanFont(doc);
            PDFont enFont = PDType1Font.HELVETICA;
            PDFont enBold = PDType1Font.HELVETICA_BOLD;

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawForm(doc, cs, krFont, enFont, enBold, data);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("[MalsoPdfGenerator] PDF 생성 실패", e);
            throw new IllegalStateException("말소등록신청서 PDF 생성 실패", e);
        }
    }

    // ================================================================
    //  전체 폼 그리기
    // ================================================================

    /**
     * 전체 폼 그리기.
     * <p>
     * A4 가용영역(786pt) 전체를 균형있게 채우도록 배치합니다.
     * <pre>
     * 영역별 높이 할당 (합계 ≈ 780pt):
     *   상단 헤더(안내문+제목+참고문)  58pt
     *   접수 정보                       34pt
     *   소유자 4행                     148pt (37*4)
     *   말소등록 원인                  148pt
     *   말소사실증명서                  34pt
     *   법률근거~수신처                130pt
     *   구분선 + 위임장 제목+문구       56pt
     *   위임장 테이블 3행              120pt (40*3)
     *   하단 (첨부서류/수수료/규격)     40pt
     *   여유                           ~18pt
     * </pre>
     */
    private void drawForm(PDDocument doc, PDPageContentStream cs, PDType0Font krFont, PDFont enFont, PDFont enBold,
                          MalsoXlsxData data) throws IOException {
        float x0 = ML;
        float x1 = PW - MR;

        // ── 공통 칼럼 ──
        float lwCol = 52f;
        float dx = x0 + lwCol;
        float dw = CW - lwCol;
        float mx = x0 + CW * 0.52f;

        // ════════════ 상단 안내문 + 제목 + 참고문 (58pt) ════════════
        drawMixed(cs, krFont, enFont, 6.5f, x0, y(0), "■ 자동차등록규칙[별지 제17호 서식]<개정 2020. 2.28>");
        drawMixedRight(cs, krFont, enFont, 6.5f, x1, y(0), "자동차365(www.car365.go.kr)에서도 신청할수 있습니다");
        drawMixedCenter(cs, krFont, enFont, 18f, x0, x1, y(24), "자 동 차  말 소  등 록  신 청 서");
        drawMixed(cs, krFont, enFont, 7f, x0, y(50), "※ [ ]에는 해당되는 곳에 √ 표를 합니다.");
        drawMixedRight(cs, krFont, enFont, 7f, x1, y(50), "(앞쪽)");

        // ════════════ 접수 정보 (34pt) ════════════
        float rt = 58;
        float rh = 34;
        float[] cols = {0, 0.25f, 0.50f, 0.75f, 1.0f};
        drawRect(cs, THICK, x0, y(rt), CW, rh);
        for (int i = 1; i < cols.length - 1; i++) {
            drawVLine(cs, THIN, x0 + CW * cols[i], y(rt), rh);
        }
        String[] hdrLabels = {"접수 번호", "접수 일시", "발급 일시", "처리 기간  즉시"};
        for (int i = 0; i < hdrLabels.length; i++) {
            drawMixedCenter(cs, krFont, enFont, 9f,
                    x0 + CW * cols[i], x0 + CW * cols[i + 1], y(rt) - rh / 2 + 3, hdrLabels[i]);
        }

        // ════════════ 소유자 섹션 4행 (148pt = 37*4) ════════════
        float ot = rt + rh;        // 92
        float oRH = 37;
        float oh = oRH * 4;        // 148

        drawRect(cs, THICK, x0, y(ot), CW, oh);
        drawVLine(cs, THIN, dx, y(ot), oh);

        // "소 유 자" 세로 텍스트
        float oCY = y(ot) - oh / 2;
        drawMixedCenter(cs, krFont, enFont, 10f, x0, dx, oCY + 20, "소");
        drawMixedCenter(cs, krFont, enFont, 10f, x0, dx, oCY, "유");
        drawMixedCenter(cs, krFont, enFont, 10f, x0, dx, oCY - 20, "자");

        // 행1: 성명 | 주민등록번호
        drawHLine(cs, THIN, dx, y(ot + oRH), dw);
        drawVLine(cs, THIN, mx, y(ot), oRH);
        drawMixed(cs, krFont, enFont, 7f, dx + 4, y(ot) - 11, "성명(명칭)");
        drawMixed(cs, krFont, enFont, 7f, mx + 4, y(ot) - 11, "주민등록번호(법인등록번호)");
        drawMixed(cs, krFont, enBold, 12f, dx + 50, y(ot) - 26, nvl(data.ownerName()));
        drawMixed(cs, krFont, enBold, 12f, mx + 95, y(ot) - 26, nvl(data.ownerIdNo()));

        // 행2: 주소
        float o2 = ot + oRH;
        drawHLine(cs, THIN, dx, y(o2 + oRH), dw);
        drawMixed(cs, krFont, enFont, 7f, dx + 4, y(o2) - 11, "주소");
        if (data.ownerAddress() != null && !data.ownerAddress().isBlank()) {
            drawMixed(cs, krFont, enFont, 10f, dx + 35, y(o2) - 24, data.ownerAddress());
        }

        // 행3: 전자우편 | 전화번호
        float o3 = o2 + oRH;
        drawHLine(cs, THIN, dx, y(o3 + oRH), dw);
        drawVLine(cs, THIN, mx, y(o3), oRH);
        drawMixed(cs, krFont, enFont, 7f, dx + 4, y(o3) - 11, "전자우편");
        drawMixed(cs, krFont, enFont, 7f, mx + 4, y(o3) - 11, "(휴대)전화번호");

        // 행4: 차량번호 | 차대번호 | 주행거리 | km
        float o4 = o3 + oRH;
        float vc1 = dw * 0.28f, vc2 = dw * 0.62f, vc3 = dw * 0.87f;
        drawVLine(cs, THIN, dx + vc1, y(o4), oRH);
        drawVLine(cs, THIN, dx + vc2, y(o4), oRH);
        drawVLine(cs, THIN, dx + vc3, y(o4), oRH);
        drawMixed(cs, krFont, enFont, 10f, dx + 6, y(o4) - 23, nvl(data.vehicleRegistrationNo()));
        drawMixed(cs, krFont, enFont, 10f, dx + vc1 + 6, y(o4) - 23, nvl(data.vehicleChassisNo()));
        if (data.vehicleMileage() != null) {
            drawMixed(cs, krFont, enFont, 10f, dx + vc2 + 6, y(o4) - 23,
                    String.format("%,d", data.vehicleMileage()));
        }
        drawMixed(cs, krFont, enFont, 8f, dx + vc3 + 6, y(o4) - 23, "km");

        // ════════════ 말소등록의 원인 (148pt) ════════════
        float rrt = ot + oh;       // 240
        float rrh = 148;
        drawRect(cs, THICK, x0, y(rrt), CW, rrh);
        drawVLine(cs, THIN, dx, y(rrt), rrh);

        float rCY = y(rrt) - rrh / 2;
        drawMixedCenter(cs, krFont, enFont, 8.5f, x0, dx, rCY + 10, "말소등록");
        drawMixedCenter(cs, krFont, enFont, 8.5f, x0, dx, rCY - 6, "의 원인");

        float cbxL = dx + 8, cbxR = x0 + CW * 0.52f;
        float cby0 = rrt + 16;
        float cbGap = 20;
        String[][] reasons = {
                {"[ ]폐차  [ ]제작·판매자에게 반품", "[ ]행정처분이행  [√]수출예정  [ ]도난  [ ]횡령·편취"},
                {"[ ]천재지변·교통사고·화재·폭파·매몰 등의 사고", "[ ]압류등록된 차량으로서 차량 초과"},
                {"[ ]연구·시험 사용 목적", "[ ]사고 원인의 규명 등 특수용도 사용 목적"},
                {"[ ]섬지역에서의 해체", "[ ]외교용 또는 SOFA차량으로서 내국민에게 양도"},
                {"[ ]도로 외의 지역에서의 한정사용 목적", "[ ]그 밖에 국토교통부장관이 인정하는 사유"},
                {"[ ]특별시장·광역시장·도지사 또는 시장·군수·구청장이 멸실 사실을 인정하는 사유", ""},
        };
        for (int i = 0; i < reasons.length; i++) {
            drawMixed(cs, krFont, enFont, 7.5f, cbxL, y(cby0 + cbGap * i), reasons[i][0]);
            if (!reasons[i][1].isEmpty()) {
                drawMixed(cs, krFont, enFont, 7.5f, cbxR, y(cby0 + cbGap * i), reasons[i][1]);
            }
        }

        // ════════════ 말소사실증명서 (34pt) ════════════
        float crt = rrt + rrh;     // 388
        float crh = 34;
        drawRect(cs, THICK, x0, y(crt), CW, crh);
        drawVLine(cs, THIN, dx, y(crt), crh);
        float cCY = y(crt) - crh / 2;
        drawMixedCenter(cs, krFont, enFont, 8f, x0, dx, cCY + 8, "말소사실");
        drawMixedCenter(cs, krFont, enFont, 8f, x0, dx, cCY - 6, "증명서");
        drawMixed(cs, krFont, enFont, 9.5f, dx + 12, cCY + 3,
                "[ √ ]발급 필요                                     [ ]발급 불필요");

        // ════════════ 법률 근거 ~ 수신처 (130pt) ════════════
        float lt = crt + crh + 14; // 436
        drawMixed(cs, krFont, enFont, 8f, x0, y(lt),
                "「자동차관리법」 제13조제1항, 「자동차등록령」 제31조제1항 및 「자동차등록규칙」 제37조에 따라");
        drawMixedCenter(cs, krFont, enFont, 10.5f, x0, x1, y(lt + 26), "자동차말소등록을 신청 합니다");

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy년  M월  d일"));
        drawMixedRight(cs, krFont, enFont, 10.5f, x1, y(lt + 58), dateStr);

        float at = lt + 78;        // 514
        drawMixed(cs, krFont, enFont, 9.5f, x0 + CW * 0.28f, y(at), "신청인  성명");
        drawMixed(cs, krFont, enBold, 13f, x0 + CW * 0.42f, y(at), nvl(data.applicantName()));
        drawMixedRight(cs, krFont, enFont, 9.5f, x1, y(at), "(서명 또는 인)");
        drawMixed(cs, krFont, enFont, 8.5f, x0 + CW * 0.36f, y(at + 18), "생년월일");
        drawMixedFit(
                cs,
                krFont,
                enFont,
                11f,
                8f,
                x1 - (x0 + CW * 0.42f) - 8f,
                x0 + CW * 0.42f,
                y(at + 18),
                nvl(data.applicantBirthDate())
        );

        float rvt = at + 44;       // 558
        drawMixedCenter(cs, krFont, enFont, 9f, x0, x1, y(rvt),
                "특별시장·광역시장·특별자치시장·도지사·특별자치도지사 또는 시장·군수·구청장 귀하");

        // ════════════ 구분선 + 위임장 제목 + 문구 (56pt) ════════════
        float sepY = rvt + 18;     // 576
        drawHLine(cs, 0.5f, x0, y(sepY), CW);

        float wt = sepY + 16;      // 592
        drawMixedCenter(cs, krFont, enFont, 15f, x0, x1, y(wt), "위 임 장");

        float wdt = wt + 26;       // 618
        drawMixedCenter(cs, krFont, enFont, 9f, x0, x1, y(wdt),
                "위 자동차의 말소등록에 따른 모든 행위를 수임자(신청인)에게 위임합니다.");

        // ════════════ 위임장 테이블 3행 (120pt = 40*3) ════════════
        // 구조: [위임자(세로)] [라벨+데이터] [서명영역(3행 병합)]
        float ptt = wdt + 22;      // 640
        float pRH = 40;
        float pth = pRH * 3;       // 120
        float plw = 36f;            // 위임자 라벨 칼럼
        float pdw = CW * 0.58f;    // 데이터 칼럼
        float signX = x0 + plw + pdw; // 서명 영역 시작 x

        // 외곽
        drawRect(cs, THICK, x0, y(ptt), CW, pth);
        // 위임자 라벨 세로선
        drawVLine(cs, THIN, x0 + plw, y(ptt), pth);
        // 라벨|데이터 구분 세로선 (위임자 칼럼 제외, 데이터 영역 내부만)
        float labelSepX = x0 + plw + 92f;
        drawVLine(cs, THIN, labelSepX, y(ptt), pth);
        // 서명 영역 세로선 (3행 전체 높이 — 병합 효과)
        drawVLine(cs, THIN, signX, y(ptt), pth);
        // 내부 가로선: 위임자 칼럼(x0~x0+plw) 제외, 서명 영역(signX~) 제외 → 병합 유지
        drawHLine(cs, THIN, x0 + plw, y(ptt + pRH), signX - x0 - plw);
        drawHLine(cs, THIN, x0 + plw, y(ptt + pRH * 2), signX - x0 - plw);

        // "위임자" 세로 텍스트 (3행 전체 병합)
        float wijCY = y(ptt) - pth / 2;
        drawMixedCenter(cs, krFont, enFont, 12f, x0, x0 + plw, wijCY + 16, "위");
        drawMixedCenter(cs, krFont, enFont, 12f, x0, x0 + plw, wijCY, "임");
        drawMixedCenter(cs, krFont, enFont, 12f, x0, x0 + plw, wijCY - 16, "자");

        float fdx = labelSepX + 10;   // 라벨 구분선 오른쪽에서 데이터 시작
        float dataCellWidth = signX - fdx - 8;

        // 행1: 성명
        drawMixedCenter(cs, krFont, enFont, 10f, x0 + plw, labelSepX, y(ptt) - pRH / 2 + 3, "성 명(명칭)");
        drawMixedFit(cs, krFont, enBold, 14f, 10f, dataCellWidth, fdx, y(ptt) - pRH / 2 + 3, nvl(data.poaName()));

        // 행2: 사업자등록번호
        drawMixedCenter(cs, krFont, enFont, 10f, x0 + plw, labelSepX, y(ptt + pRH) - pRH / 2 + 3, "사업자등록번호");
        drawMixedFit(cs, krFont, enBold, 14f, 10f, dataCellWidth, fdx, y(ptt + pRH) - pRH / 2 + 3, nvl(data.poaBizNo()));

        // 행3: 주소
        drawMixedCenter(cs, krFont, enFont, 10f, x0 + plw, labelSepX, y(ptt + pRH * 2) - pRH / 2 + 3, "주  소");
        drawMixedFit(cs, krFont, enFont, 12f, 9f, dataCellWidth, fdx, y(ptt + pRH * 2) - pRH / 2 + 3, nvl(data.poaAddress()));

        // 서명 영역: "(서명 또는 인)" 텍스트 + 사인방 이미지
        drawMixedCenter(cs, krFont, enFont, 10f, signX, x1, y(ptt) - 10, "(서명 또는 인)");

        // 사인방 이미지 삽입 (서명 영역 3행 병합 공간에)
        if (data.poaSignImageBytes() != null && data.poaSignImageBytes().length > 0) {
            try {
                PDImageXObject signImg = PDImageXObject.createFromByteArray(
                        doc, data.poaSignImageBytes(), "sign.png");
                float signAreaW = x1 - signX - 10;       // 서명 영역 가용 너비 (양쪽 5pt 패딩)
                float signAreaH = pth - 24;               // "(서명 또는 인)" 텍스트 아래 공간
                float imgW = signImg.getWidth();
                float imgH = signImg.getHeight();
                float scale = Math.min(signAreaW / imgW, signAreaH / imgH);
                float drawW = imgW * scale;
                float drawH = imgH * scale;
                // 서명 영역 중앙에 배치 (텍스트 아래)
                float imgX = signX + (x1 - signX - drawW) / 2f;
                float imgY = y(ptt) - 18 - (signAreaH + drawH) / 2f;
                cs.drawImage(signImg, imgX, imgY, drawW, drawH);
            } catch (Exception e) {
                log.warn("[MalsoPdfGenerator] 서명 이미지 삽입 실패", e);
            }
        }

        // ════════════ 하단 (40pt) ════════════
        float ft = ptt + pth + 10; // 770
        drawMixed(cs, krFont, enFont, 8.5f, x0, y(ft), "첨부서류  뒤쪽참조");
        drawMixedRight(cs, krFont, enFont, 8.5f, x1, y(ft), "수수료");
        drawMixedRight(cs, krFont, enBold, 11f, x1, y(ft + 14), "1,000원");
        drawMixedCenter(cs, krFont, enFont, 7f, x0, x1, y(ft + 32),
                "210mm x 297mm[백상지 또는 중질지80g/m2]");
        // 하단 끝 = 770 + 32 = 802pt → y(802) = 841.89 - 28 - 802 = 11.89pt (하단 마진 내 ✓)
    }

    // ================================================================
    //  혼합 폰트 텍스트 렌더링
    // ================================================================

    /** 코드포인트가 CJK(한중일) 또는 한글인지 판별 */
    private static boolean isCjk(int cp) {
        if (cp >= 0xAC00 && cp <= 0xD7AF) return true; // 한글 음절
        if (cp >= 0x1100 && cp <= 0x11FF) return true;  // 한글 자모
        if (cp >= 0x3130 && cp <= 0x318F) return true;  // 한글 호환 자모
        if (cp >= 0x4E00 && cp <= 0x9FFF) return true;  // CJK Unified
        if (cp >= 0x3400 && cp <= 0x4DBF) return true;
        if (cp >= 0x3000 && cp <= 0x303F) return true;  // CJK symbols
        if (cp >= 0xFF00 && cp <= 0xFFEF) return true;  // fullwidth
        if (cp >= 0x3200 && cp <= 0x33FF) return true;  // enclosed CJK
        return false;
    }

    /** 텍스트를 실제 렌더링 폰트 단위 세그먼트로 분할 */
    private record Segment(PDFont font, String text) {}

    private static boolean canRender(PDFont font, String text) {
        try {
            font.getStringWidth(text);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static List<Segment> splitMixed(PDFont krFont, PDFont enFont, String text) {
        List<Segment> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;

        StringBuilder sb = new StringBuilder();
        PDFont currentFont = null;

        for (int i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));

            PDFont preferred = isCjk(cp) ? krFont : enFont;
            PDFont fallback = preferred == krFont ? enFont : krFont;
            PDFont targetFont = preferred;
            String renderText = ch;

            if (!canRender(targetFont, renderText)) {
                if (canRender(fallback, renderText)) {
                    targetFont = fallback;
                } else {
                    renderText = "?";
                    if (!canRender(targetFont, renderText) && canRender(fallback, renderText)) {
                        targetFont = fallback;
                    }
                }
            }

            // 극단적인 경우(두 폰트 모두 미지원)는 공백으로 치환해 렌더링 실패를 막는다.
            if (!canRender(targetFont, renderText)) {
                if (canRender(preferred, " ")) {
                    targetFont = preferred;
                    renderText = " ";
                } else if (canRender(fallback, " ")) {
                    targetFont = fallback;
                    renderText = " ";
                } else {
                    continue;
                }
            }

            if (currentFont != targetFont) {
                if (sb.length() > 0) {
                    result.add(new Segment(currentFont, sb.toString()));
                }
                sb.setLength(0);
                currentFont = targetFont;
            }
            sb.append(renderText);
        }
        if (sb.length() > 0) {
            result.add(new Segment(currentFont, sb.toString()));
        }
        return result;
    }

    /** 혼합 텍스트 너비 계산 */
    private float mixedWidth(PDFont krFont, PDFont enFont, float size, String text) throws IOException {
        float w = 0;
        for (Segment seg : splitMixed(krFont, enFont, text)) {
            w += seg.font().getStringWidth(seg.text()) / 1000f * size;
        }
        return w;
    }

    /** 혼합 텍스트 왼쪽 정렬 */
    private void drawMixed(PDPageContentStream cs, PDFont krFont, PDFont enFont,
                           float size, float x, float yBottom, String text) throws IOException {
        if (text == null || text.isEmpty()) return;
        float cx = x;
        for (Segment seg : splitMixed(krFont, enFont, text)) {
            float segWidth = seg.font().getStringWidth(seg.text()) / 1000f * size;
            boolean began = false;
            cs.beginText();
            began = true;
            try {
                cs.setFont(seg.font(), size);
                cs.newLineAtOffset(cx, yBottom);
                cs.showText(seg.text());
            } finally {
                if (began) {
                    cs.endText();
                }
            }
            cx += segWidth;
        }
    }

    /** 혼합 텍스트 오른쪽 정렬 */
    private void drawMixedRight(PDPageContentStream cs, PDFont krFont, PDFont enFont,
                                float size, float xRight, float yBottom, String text) throws IOException {
        float w = mixedWidth(krFont, enFont, size, text);
        drawMixed(cs, krFont, enFont, size, xRight - w, yBottom, text);
    }

    /** 혼합 텍스트 가운데 정렬 */
    private void drawMixedCenter(PDPageContentStream cs, PDFont krFont, PDFont enFont,
                                 float size, float xLeft, float xRight, float yBottom,
                                 String text) throws IOException {
        float w = mixedWidth(krFont, enFont, size, text);
        drawMixed(cs, krFont, enFont, size, xLeft + (xRight - xLeft - w) / 2f, yBottom, text);
    }

    /** 셀 너비를 넘기면 글자 크기를 자동 축소해 한 줄에 맞춰 출력 */
    private void drawMixedFit(PDPageContentStream cs, PDFont krFont, PDFont enFont,
                              float preferredSize, float minSize, float maxWidth,
                              float x, float yBottom, String text) throws IOException {
        if (text == null || text.isBlank()) return;
        float size = preferredSize;
        while (size > minSize && mixedWidth(krFont, enFont, size, text) > maxWidth) {
            size -= 0.5f;
        }
        if (size < minSize) size = minSize;
        drawMixed(cs, krFont, enFont, size, x, yBottom, text);
    }

    // ================================================================
    //  그리기 유틸
    // ================================================================

    private void drawRect(PDPageContentStream cs, float lineW, float x, float yTop, float w, float h)
            throws IOException {
        cs.setLineWidth(lineW);
        cs.addRect(x, yTop - h, w, h);
        cs.stroke();
    }

    private void drawHLine(PDPageContentStream cs, float lineW, float x, float yPos, float w)
            throws IOException {
        cs.setLineWidth(lineW);
        cs.moveTo(x, yPos);
        cs.lineTo(x + w, yPos);
        cs.stroke();
    }

    private void drawVLine(PDPageContentStream cs, float lineW, float x, float yTop, float h)
            throws IOException {
        cs.setLineWidth(lineW);
        cs.moveTo(x, yTop);
        cs.lineTo(x, yTop - h);
        cs.stroke();
    }

    // ================================================================
    //  폰트 로드
    // ================================================================

    private PDType0Font loadKoreanFont(PDDocument doc) throws IOException {
        // 1) classpath 폰트
        for (String path : FONT_PATHS) {
            try (InputStream is = new ClassPathResource(path).getInputStream()) {
                PDType0Font font = PDType0Font.load(doc, is);
                log.info("[MalsoPdfGenerator] 한국어 폰트 로드: classpath:{}", path);
                return font;
            } catch (Exception e) {
                log.debug("[MalsoPdfGenerator] classpath 폰트 {} 미발견", path);
            }
        }

        // 2) 시스템 폰트
        for (String path : SYSTEM_FONT_PATHS) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    PDType0Font font = PDType0Font.load(doc, f);
                    log.info("[MalsoPdfGenerator] 한국어 폰트 로드: {}", path);
                    return font;
                }
            } catch (Exception e) {
                log.debug("[MalsoPdfGenerator] 시스템 폰트 {} 로드 실패", path);
            }
        }

        throw new IOException("한국어 폰트를 찾을 수 없습니다. classpath:fonts/ 에 NanumGothic.ttf를 추가하세요.");
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }
}
