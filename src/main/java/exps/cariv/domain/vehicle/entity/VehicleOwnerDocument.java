package exps.cariv.domain.vehicle.entity;

import exps.cariv.domain.document.entity.Document;
import exps.cariv.domain.document.entity.DocumentRefType;
import exps.cariv.domain.document.entity.DocumentStatus;
import exps.cariv.domain.document.entity.DocumentType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@DiscriminatorValue("VEHICLE_OWNER_DOC")
@Table(name = "vehicle_owner_document")
public class VehicleOwnerDocument extends Document {

    public static VehicleOwnerDocument createNew(Long companyId,
                                                 Long uploadedByUserId,
                                                 Long vehicleId,
                                                 String s3Key,
                                                 String originalFilename,
                                                 String contentType,
                                                 long sizeBytes) {
        VehicleOwnerDocument doc = new VehicleOwnerDocument();
        doc.companyId = companyId;
        doc.type = DocumentType.ID_CARD;
        doc.status = DocumentStatus.CONFIRMED;
        doc.refType = DocumentRefType.VEHICLE;
        doc.refId = vehicleId;
        doc.s3Key = s3Key;
        doc.uploadedByUserId = uploadedByUserId;
        doc.originalFilename = originalFilename;
        doc.contentType = contentType;
        doc.sizeBytes = sizeBytes;
        doc.uploadedAt = Instant.now();
        return doc;
    }
}
