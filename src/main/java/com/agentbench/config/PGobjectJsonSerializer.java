package com.agentbench.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.postgresql.util.PGobject;
import org.springframework.boot.jackson.JsonComponent;

import java.io.IOException;

/** #6: jsonb/json columns come back from JdbcTemplate (jdbc.queryForList/queryForMap in BenchController)
 *  as a raw org.postgresql.util.PGobject - the pg driver's handle for a column type it doesn't map
 *  itself. With no customization, Jackson bean-serializes it via its getType()/getValue() getters into
 *  {"type":"jsonb","value":"<escaped string>"} instead of real nested JSON. One global serializer fixes
 *  every endpoint at once (jobs.argv, experiments.params/comparison, runs.manifest/oracle/metrics/
 *  validity_reasons, check_results.detail), instead of patching each call site. */
@JsonComponent
public class PGobjectJsonSerializer extends StdSerializer<PGobject> {

    public PGobjectJsonSerializer() { super(PGobject.class); }

    @Override
    public void serialize(PGobject value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        // an empty string is not valid JSON text: writeRawValue("") would emit zero bytes and leave a
        // dangling token (e.g. {"field":,}), corrupting the whole response body - fall back to a JSON string
        if (value.getValue() != null && !value.getValue().isEmpty()
                && ("json".equals(value.getType()) || "jsonb".equals(value.getType())))
            gen.writeRawValue(value.getValue());   // already valid JSON text - write it in place, not re-escaped
        else
            gen.writeString(value.getValue());
    }
}
