package com.easytrading.backend.instrument;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * db/schema.sql stores `instrument.type` as lowercase text, enforced by
 * CHECK (type IN ('forex', 'crypto', 'stock')). Java enum constants are
 * uppercase by convention (FOREX/CRYPTO/STOCK). Without this converter,
 * Hibernate would try to write the literal "FOREX" and the CHECK
 * constraint would reject every insert.
 */
@Converter(autoApply = true)
public class InstrumentTypeConverter implements AttributeConverter<InstrumentType, String> {

    @Override
    public String convertToDatabaseColumn(InstrumentType attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public InstrumentType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : InstrumentType.valueOf(dbData.toUpperCase());
    }
}
