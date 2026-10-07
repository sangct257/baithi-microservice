package ra.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class GeocodingResponse {
    @JsonProperty("lat")
    private Double lat;

    @JsonProperty("lon")
    private Double lng; // Ánh xạ từ field "lon" của OpenStreetMap sang lng

    @JsonProperty("display_name")
    private String displayName;
}