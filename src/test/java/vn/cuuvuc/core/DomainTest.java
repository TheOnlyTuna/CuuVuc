package vn.cuuvuc.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DomainTest {
    @Test void cultivationBoundaries() throws IOException {
        Domain.Catalog catalog = CatalogFile.read(getClass().getResourceAsStream("/catalog.properties"));
        assertEquals("Phàm nhân", Domain.stage(99, catalog.stages()).name());
        assertEquals("Luyện Khí", Domain.stage(100, catalog.stages()).name());
        assertEquals("Luyện Khí", Domain.stage(499, catalog.stages()).name());
        assertEquals("Trúc Cơ", Domain.stage(500, catalog.stages()).name());
        assertEquals("Nguyên Anh", Domain.stage(Long.MAX_VALUE, catalog.stages()).name());
        assertThrows(IllegalArgumentException.class, () -> Domain.stage(-1, catalog.stages()));
    }

    @Test void boundedIntegerQuantities() {
        assertEquals(1, Domain.quantity("1"));
        assertEquals(1000, Domain.quantity("1000"));
        for (String input : new String[]{"0", "-1", "1001", "1.5", "999999999999999999999", "abc"})
            assertThrows(IllegalArgumentException.class, () -> Domain.quantity(input));
    }

    @Test void catalogIsImmutableAndReferencesResolve() throws IOException {
        Domain.Catalog catalog = CatalogFile.read(getClass().getResourceAsStream("/catalog.properties"));
        assertEquals(3, catalog.resources().size());
        assertEquals(5, Domain.recipe(catalog, "ket_tinh").inputCount());
        assertThrows(UnsupportedOperationException.class, () -> catalog.resources().clear());
        assertThrows(IllegalArgumentException.class, () -> Domain.resource(catalog, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> Domain.recipe(catalog, "invalid"));
    }

    @Test void malformedEconomyDefinitionsFail() {
        assertThrows(IllegalArgumentException.class, () -> CatalogFile.parseResource("x", "x,STONE,1,2,3"));
        assertThrows(IllegalArgumentException.class, () -> CatalogFile.parseRecipe("x", "ore,0,bar,1"));
        String malformed = "resource.x=X,STONE,1,2,1\nstage.0=A,0\nstage.1=B,0";
        assertThrows(IllegalArgumentException.class, () -> CatalogFile.read(new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8))));
    }
}
