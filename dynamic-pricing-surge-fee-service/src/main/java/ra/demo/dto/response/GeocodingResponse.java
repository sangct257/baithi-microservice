package ra.demo.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GeocodingResponse {
    @JsonProperty("lat")
    private Double lat;

    @JsonProperty("lon")
    private Double lng; // Ánh xạ từ field "lon" của OpenStreetMap sang lng

    @JsonProperty("display_name")
    private String displayName;
}