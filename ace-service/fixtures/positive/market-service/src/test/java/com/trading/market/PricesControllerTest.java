package com.trading.market;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PricesController.class)
class PricesControllerTest {
    @Autowired MockMvc mvc;

    @Test void knownSymbolIsPricedAsString() throws Exception {
        mvc.perform(get("/prices/aapl")).andExpect(status().isOk())
           .andExpect(jsonPath("$.symbol").value("AAPL")).andExpect(jsonPath("$.price").value("10.00"));
        mvc.perform(get("/prices/EURUSD")).andExpect(jsonPath("$.price").value("1.0850"));
    }
    @Test void unknownSymbolIs404() throws Exception {
        mvc.perform(get("/prices/NOPE")).andExpect(status().isNotFound());
    }
    @Test void healthIs200() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
