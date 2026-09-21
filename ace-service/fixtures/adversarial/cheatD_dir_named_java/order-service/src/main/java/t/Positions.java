package t;
import java.math.BigDecimal;
public class Positions {
  public BigDecimal holdingsAt(java.util.List<Txn> log){
    BigDecimal q = BigDecimal.ZERO;
    for (Txn t : log) { q = q.add(t.qty); }   // BUG: SELL added, never subtracted
    return q;
  }
}
class Txn { String kind; java.math.BigDecimal qty; }
