package com.vibevault.paymentgateway.models;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
@ToString(callSuper = false)
@EqualsAndHashCode(callSuper = false)
@EntityListeners(AuditingEntityListener.class)
public class BaseModel {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private UUID id;

    @CreatedDate
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Date createdAt;

    @LastModifiedDate
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Date lastModifiedAt;

    @Column(name = "is_deleted")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean isDeleted;

    @CreatedBy
    private UUID createdBy;

    @LastModifiedBy
    private UUID lastModifiedBy;

    @Version
    private Integer version;
}
