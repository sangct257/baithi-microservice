package ra.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.security.DenyAll;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true) // Bỏ qua các field không cần thiết từ API
public class GeocodingResponse {
    private Double lat;

    private Double lng;

    @JsonProperty("display_name")
    private String displayName;

    // Custom constructor nhanh gọn cho Service
    public GeocodingResponse(Double lat, Double lng) {
        this.lat = lat;
        this.lng = lng;
    }
}