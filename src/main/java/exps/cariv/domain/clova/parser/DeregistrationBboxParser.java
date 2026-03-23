package exps.cariv.domain.clova.parser;

import exps.cariv.domain.clova.dto.OcrWord;
import exps.cariv.domain.clova.dto.TextLine;
import exps.cariv.domain.clova.dto.VehicleDeregistration;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말소사실증명서 bbox 기반 파서 (CLOVA OCR용)
 * 앵커 단어의 좌표를 기준으로 인접 값 추출
 */
@Slf4j
public class DeregistrationBboxParser {

    private final List<OcrWord> words;
    private final List<TextLine> lines;

    // 문서 크기 (좌표 정규화용)
    private final double docWidth;
    private final double docHeight;

    public DeregistrationBboxParser(List<OcrWord> words, List<TextLine> lines) {
        this.words = words;
        this.lines = lines;
        this.docWidth = words.stream().mapToDouble(OcrWord::rightX).max().orElse(1);
        this.docHeight = words.stream().mapToDouble(OcrWord::bottomY).max().orElse(1);
    }

    public VehicleDeregistration parse() {
        VehicleDeregistration r = new VehicleDeregistration();

        r.setVehicleNo(extractVehicleNo());
        r.setCarType(extractCarType());
        r.setMileage(extractMileage());
        r.setModelName(extractModelName());
        r.setVin(extractVin());
        r.setEngineType(extractEngineType());
        r.setModelYear(extractModelYear());
        r.setVehicleUse(extractVehicleUse());
        r.setSpecManagementNo(extractSpecManagementNo());
        r.setFirstRegistratedAt(extractFirstRegistratedAt());
        r.setOwnerName(extractOwnerName());
        r.setOwnerId(extractOwnerId());
        r.setDeregistrationDate(extractDeregistrationDate());
        r.setDeregistrationReason(extractDeregistrationReason());
        r.setCertificateUse(extractCertificateUse());
        r.setSeizureCount(extractSeizureCount());
        r.setMortgageCount(extractMortgageCount());
        r.setBusinessUsePeriod(extractBusinessUsePeriod());
        r.setIssueDate(extractIssueDate());
        r.setIssuer(extractIssuer());

        int filled = countFilled(r);
        r.setQualityScore(Math.min(1.0, filled / 15.0));
        r.setQualityGatePassed(filled >= 6);
        r.setNeedsRetry(filled < 4);
        r.setDocumentType("VEHICLE_DEREGISTRATION");
        r.setDocumentTypeScore(isDeregistration() ? 0.95 : 0.3);

        log.info("DeregistrationBboxParser: filled {}/20 fields", filled);
        return r;
    }

    // ═══ 라인 텍스트 기반 추출 (bbox 좌표로 그룹핑된 TextLine 활용) ═══

    private String extractVehicleNo() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("등록번호") || text.contains("Registration")) {
                Matcher m = Pattern.compile("(\\d{2,3}\\s*[가-힣]{1,2}\\s*\\d{4})").matcher(text);
                if (m.find()) return m.group(1).replaceAll("\\s+", "");
            }
            // 번호만 있는 줄 (라벨 없이)
            Matcher m = Pattern.compile("^\\s*(\\d{2,3}[가-힣]{1,2}\\d{4})\\s*$").matcher(text.replace(" ", ""));
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String extractCarType() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("차종")) {
                Matcher m = Pattern.compile("(승용|승합|화물|특수|이륜)").matcher(text);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private String extractMileage() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("주행거리") || text.contains("Mileage")) {
                Matcher m = Pattern.compile("(\\d{3,7})").matcher(text.replace(",", ""));
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private String extractModelName() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("차명")) {
                // "차명" 앵커 단어 찾기
                OcrWord anchor = findWordContaining(line, "차명");
                if (anchor != null) {
                    // 앵커 오른쪽에 있는 값
                    String after = line.textAfterX(anchor.rightX()).trim();
                    after = after.replaceAll("\\s*(?:차대번호|Chassis|Model|KNAJ|KNAL|KNAG|KNA[A-Z]).*", "").trim();
                    if (!after.isEmpty()) return after;
                }
                // 다음 줄
                int idx = lines.indexOf(line);
                if (idx + 1 < lines.size()) {
                    String next = lines.get(idx + 1).fullText().trim();
                    next = next.replaceAll("\\s*(?:Chassis|Model|[A-Z]{4,}\\d+).*", "").trim();
                    if (!next.isEmpty() && !next.startsWith("Model") && next.length() < 20) return next;
                }
            }
        }
        return null;
    }

    private String extractVin() {
        // 전체 단어에서 17자 VIN 검색
        for (OcrWord w : words) {
            String upper = w.getText().replace(" ", "").toUpperCase();
            if (upper.length() == 17 && upper.matches("[A-HJ-NPR-Z0-9]{17}")) {
                if (isValidVin(upper)) return upper;
            }
        }
        // 라인 텍스트에서 검색
        for (TextLine line : lines) {
            String compact = line.compactText().toUpperCase();
            Matcher m = Pattern.compile("([A-HJ-NPR-Z0-9]{17})").matcher(compact);
            while (m.find()) {
                if (isValidVin(m.group(1))) return m.group(1);
            }
        }
        return null;
    }

    private String extractEngineType() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("원동기형식") || text.contains("원동기")) {
                OcrWord anchor = findWordContaining(line, "원동기");
                if (anchor != null) {
                    String after = line.textAfterX(anchor.rightX()).trim();
                    // 엔진코드 패턴
                    Matcher m = Pattern.compile("([A-Z]\\d[A-Z0-9]{1,8})").matcher(after);
                    if (m.find()) return m.group(1);
                    Matcher m2 = Pattern.compile("([A-Z]{1,2}\\d{1,2}[A-Z]{1,3})").matcher(after);
                    if (m2.find()) return m2.group(1);
                }
            }
        }
        // 독립 단어로 엔진코드 검색
        for (OcrWord w : words) {
            String t = w.getText().trim();
            if (t.matches("[A-Z]\\d[A-Z]{1,3}\\d?") && t.length() >= 3 && t.length() <= 8) {
                return t;
            }
        }
        return null;
    }

    private Integer extractModelYear() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("모델연도") || text.contains("Model Year")) {
                Matcher m = Pattern.compile("(19|20)\\d{2}").matcher(text);
                if (m.find()) {
                    int year = Integer.parseInt(m.group());
                    if (year >= 1990 && year <= 2030) return year;
                }
            }
        }
        // 모델연도 앵커 근처 단어
        OcrWord anchor = findAnchorWord("모델연도");
        if (anchor != null) {
            for (OcrWord w : words) {
                if (Math.abs(w.centerY() - anchor.centerY()) < anchor.getHeight() * 3) {
                    Matcher m = Pattern.compile("^(19|20)\\d{2}$").matcher(w.getText().trim());
                    if (m.find()) return Integer.parseInt(m.group());
                }
            }
        }
        return null;
    }

    private String extractVehicleUse() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("용도") && !text.contains("증명서")) {
                if (text.contains("자가용")) return "자가용";
                if (text.contains("사업용")) return "사업용";
            }
        }
        // 독립 단어로
        for (OcrWord w : words) {
            if (w.getText().contains("자가용")) return "자가용";
            if (w.getText().contains("사업용")) return "사업용";
        }
        return null;
    }

    private String extractSpecManagementNo() {
        for (TextLine line : lines) {
            Matcher m = Pattern.compile("(A\\d{2}-\\d-\\d{5}-\\d{4}-\\d{4})").matcher(line.fullText());
            if (m.find()) return m.group(1);
        }
        // 단어 조합
        for (OcrWord w : words) {
            Matcher m = Pattern.compile("(A\\d{2}-\\d-\\d{5}-\\d{4}-\\d{4})").matcher(w.getText());
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private String extractFirstRegistratedAt() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("최초등록일") || text.contains("Date of Initial")) {
                Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(text);
                if (m.find()) return m.group(1);
            }
        }
        // 앵커 근처
        OcrWord anchor = findAnchorWord("최초등록일");
        if (anchor != null) {
            String dateStr = findValueNear(anchor, "\\d{4}-\\d{2}-\\d{2}");
            if (dateStr != null) return dateStr;
        }
        return null;
    }

    private String extractOwnerName() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("성명") || text.contains("소유자")) {
                if (text.contains("유의사항")) continue;
                Matcher m = Pattern.compile("(?:성명|소유자)[^가-힣]*([가-힣()]+)").matcher(text);
                if (m.find()) {
                    String name = m.group(1).replaceAll("[*+]?\\(?상품용\\)?[*+]?", "").trim();
                    if (!name.isEmpty() && !name.equals("성명") && !name.equals("소유자")) return name;
                }
            }
        }
        return null;
    }

    private String extractOwnerId() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("생년월일") || text.contains("법인등록번호") || text.contains("Birth")) {
                Matcher m = Pattern.compile("(\\d{6})\\s*-\\s*([\\d*]{1,7})").matcher(text);
                if (m.find()) return m.group(1) + "-" + m.group(2);
            }
        }
        return null;
    }

    private String extractDeregistrationDate() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if ((text.contains("말소") && text.contains("등록") && text.contains("일"))
                    || text.contains("De-registration")) {
                if (text.contains("구분") || text.contains("사유") || text.contains("Reason")) continue;
                Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(text);
                if (m.find()) return m.group(1);
            }
        }
        // 앵커 근처에서 날짜 검색
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).fullText();
            if (text.contains("말소") && text.contains("일")) {
                if (i + 1 < lines.size()) {
                    Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})").matcher(lines.get(i + 1).fullText());
                    if (m.find()) return m.group(1);
                }
            }
        }
        return null;
    }

    private String extractDeregistrationReason() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("수출")) return extractReasonValue(text, "수출");
            if (text.contains("해체")) return extractReasonValue(text, "해체");
            if (text.contains("직권")) return extractReasonValue(text, "직권");
            if (text.contains("폐차")) return extractReasonValue(text, "폐차");
        }
        // 말소등록구분 근처에서
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i).fullText();
            if (text.contains("말소") && text.contains("구분")) {
                for (int j = i; j <= Math.min(lines.size() - 1, i + 3); j++) {
                    String lt = lines.get(j).fullText();
                    if (lt.contains("수출")) return extractReasonValue(lt, "수출");
                    if (lt.contains("해체")) return extractReasonValue(lt, "해체");
                    if (lt.contains("직권")) return extractReasonValue(lt, "직권");
                }
            }
        }
        return null;
    }

    private String extractReasonValue(String text, String keyword) {
        String compact = text.replace(" ", "");
        Matcher m = Pattern.compile("([가-힣()]*" + keyword + "[가-힣()]*)").matcher(compact);
        if (m.find()) {
            String reason = m.group(1).replaceAll("저당권.*", "").replaceAll("압류.*", "")
                    .replaceAll("권리관계.*", "").replaceAll("여부.*", "").trim();
            if (!reason.isEmpty()) return reason;
        }
        return keyword + "말소";
    }

    private String extractCertificateUse() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("증명용")) return "증명용";
            if (text.contains("수출용")) return "수출용";
            if (text.contains("폐차용")) return "폐차용";
        }
        return null;
    }

    private String extractSeizureCount() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("압류") || text.contains("압 류")) {
                Matcher m = Pattern.compile("(\\d+)\\s*건").matcher(text);
                if (m.find() && !text.contains("저당권")) return m.group(1);
            }
        }
        // 0 건 패턴
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).fullText().contains("압류") || lines.get(i).fullText().contains("Seizure")) {
                for (int j = i; j <= Math.min(lines.size() - 1, i + 2); j++) {
                    Matcher m = Pattern.compile("(\\d+)\\s*건").matcher(lines.get(j).fullText());
                    if (m.find() && !lines.get(j).fullText().contains("저당권")) return m.group(1);
                }
            }
        }
        return null;
    }

    private String extractMortgageCount() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("저당권") || text.contains("Mortgage")) {
                Matcher m = Pattern.compile("(\\d+)\\s*건").matcher(text);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    private String extractBusinessUsePeriod() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("사업용") && text.contains("사용기간")) {
                Matcher m = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*[~\\-]\\s*(\\d{4}-\\d{2}-\\d{2})").matcher(text);
                if (m.find()) return m.group(1) + " ~ " + m.group(2);
            }
        }
        return null;
    }

    private String extractIssueDate() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("발행") || text.contains("Date of Issu")) {
                Matcher m = Pattern.compile("(\\d{4})[.\\-/](\\d{1,2})[.\\-/](\\d{1,2})").matcher(text);
                if (m.find()) return m.group(1) + "-" + String.format("%02d", Integer.parseInt(m.group(2)))
                        + "-" + String.format("%02d", Integer.parseInt(m.group(3)));
            }
        }
        return null;
    }

    private String extractIssuer() {
        for (TextLine line : lines) {
            String text = line.fullText();
            if (text.contains("청장") || text.contains("시장")) {
                String issuer = text.replaceAll("\\s+", "").trim();
                if (issuer.length() > 3 && issuer.length() < 30) return issuer;
            }
        }
        return null;
    }

    // ═══ 유틸 ═══

    private OcrWord findWordContaining(TextLine line, String keyword) {
        return line.getWords().stream()
                .filter(w -> w.getText().contains(keyword))
                .findFirst().orElse(null);
    }

    private OcrWord findAnchorWord(String keyword) {
        return words.stream()
                .filter(w -> w.getText().contains(keyword))
                .findFirst().orElse(null);
    }

    private String findValueNear(OcrWord anchor, String pattern) {
        return words.stream()
                .filter(w -> Math.abs(w.centerY() - anchor.centerY()) < anchor.getHeight() * 2)
                .filter(w -> w.getX() > anchor.rightX())
                .sorted(Comparator.comparingDouble(OcrWord::getX))
                .map(OcrWord::getText)
                .filter(t -> t.matches(pattern))
                .findFirst().orElse(null);
    }

    private boolean isValidVin(String vin) {
        if (vin == null || vin.length() != 17) return false;
        if (vin.matches(".*[IOQ].*")) return false;
        int alpha = 0, digit = 0;
        for (char c : vin.toCharArray()) {
            if (Character.isLetter(c)) alpha++;
            if (Character.isDigit(c)) digit++;
        }
        return alpha >= 3 && digit >= 5;
    }

    private boolean isDeregistration() {
        for (OcrWord w : words) {
            if (w.getText().contains("말소")) return true;
        }
        return false;
    }

    private int countFilled(VehicleDeregistration r) {
        int c = 0;
        if (r.getVehicleNo() != null) c++;
        if (r.getCarType() != null) c++;
        if (r.getMileage() != null) c++;
        if (r.getModelName() != null) c++;
        if (r.getVin() != null) c++;
        if (r.getEngineType() != null) c++;
        if (r.getModelYear() != null) c++;
        if (r.getVehicleUse() != null) c++;
        if (r.getSpecManagementNo() != null) c++;
        if (r.getFirstRegistratedAt() != null) c++;
        if (r.getOwnerName() != null) c++;
        if (r.getOwnerId() != null) c++;
        if (r.getDeregistrationDate() != null) c++;
        if (r.getDeregistrationReason() != null) c++;
        if (r.getCertificateUse() != null) c++;
        if (r.getSeizureCount() != null) c++;
        if (r.getMortgageCount() != null) c++;
        if (r.getBusinessUsePeriod() != null) c++;
        if (r.getIssueDate() != null) c++;
        if (r.getIssuer() != null) c++;
        return c;
    }
}
