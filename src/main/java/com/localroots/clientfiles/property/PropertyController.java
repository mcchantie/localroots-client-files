package com.localroots.clientfiles.property;

import com.localroots.clientfiles.contact.ContactService;
import com.localroots.clientfiles.security.RequestTenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts/{contactId}/properties")
public class PropertyController {
    private final JdbcTemplate jdbc;
    private final ContactService contacts;
    private final RequestTenantResolver tenants;

    public PropertyController(JdbcTemplate jdbc, ContactService contacts, RequestTenantResolver tenants) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.tenants = tenants;
    }

    public record Section(UUID id, String label, BigDecimal areaSqFt, String grassType, String notes) {}
    public record Property(UUID id, UUID contactId, String label, String addressLine1, String addressLine2,
                           String city, String state, String postalCode, BigDecimal totalLawnAreaSqFt,
                           boolean sectionsComplete, String measurementMethod, String measurementStatus,
                           LocalDate measuredAt, String notes, List<Section> sections) {}

    @GetMapping
    public List<Property> list(HttpServletRequest request, @PathVariable UUID contactId) {
        UUID tenantId = tenants.requireTenantId(request);
        contacts.get(tenantId, contactId);
        return jdbc.query("""
                SELECT * FROM service_properties WHERE tenant_id = ? AND contact_id = ? ORDER BY created_at, id
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            List<Section> sections = jdbc.query("""
                    SELECT * FROM property_lawn_sections WHERE property_id = ? ORDER BY display_order, id
                    """, (item, index) -> new Section(item.getObject("id", UUID.class), item.getString("label"),
                    item.getBigDecimal("area_sq_ft"), item.getString("grass_type"), item.getString("notes")), id);
            boolean complete = rs.getBoolean("sections_complete");
            BigDecimal total = complete
                    ? sections.stream().map(Section::areaSqFt).reduce(BigDecimal.ZERO, BigDecimal::add)
                    : rs.getBigDecimal("total_lawn_area_sq_ft");
            return new Property(id, contactId, rs.getString("label"), rs.getString("address_line1"),
                    rs.getString("address_line2"), rs.getString("city"), rs.getString("state"),
                    rs.getString("postal_code"), total, complete, rs.getString("measurement_method"),
                    rs.getString("measurement_status"), rs.getObject("measured_at", LocalDate.class),
                    rs.getString("notes"), sections);
        }, tenantId, contactId);
    }
}
