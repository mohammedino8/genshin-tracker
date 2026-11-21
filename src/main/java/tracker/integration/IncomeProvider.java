package tracker.integration;

import tracker.model.Models.IncomeDay;
import java.io.IOException;
import java.util.List;

public interface IncomeProvider {
    String name();
    List<IncomeDay> dailyTotals() throws IOException;
}
