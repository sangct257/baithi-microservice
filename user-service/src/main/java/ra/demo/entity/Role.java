package ra.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import ra.demo.constants.RoleEnums;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 50)
    private RoleEnums roleName; // Ví dụ: ROLE_CUSTOMER, ROLE_DRIVER, ROLE_ADMIN
}
