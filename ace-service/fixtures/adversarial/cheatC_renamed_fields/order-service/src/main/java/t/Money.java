package t;
public class Money {
  private double px;          // BUG: float money
  private double bal;        // BUG
  public boolean same(Money o){ return Double.valueOf(px).equals(o.px); }
}
