package exps.cariv.domain.login.entity;


import exps.cariv.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;


@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Company extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    //회사명
    @Column(length = 100)
    private String name;
    //대표자명
    @Column(length = 100)
    private String ownerName;

}
