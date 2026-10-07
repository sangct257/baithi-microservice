package ra.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import ra.demo.constants.VehicleType;

@Entity
@Table(name = "vehicles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "driver_id", nullable = false, unique = true)
    private DriverProfile driverProfile;

    @Column(nullable = false, unique = true)
    private String licensePlate; // Biển số xe

    @Column(nullable = false)
    private String brand; // Hãng xe (Honda, Toyota...)

    @Column(nullable = false)
    private String model; // Dòng xe (Wave Alpha, Vios...)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VehicleType vehicleType; // BIKE, CAR_4_SEAT...
}
