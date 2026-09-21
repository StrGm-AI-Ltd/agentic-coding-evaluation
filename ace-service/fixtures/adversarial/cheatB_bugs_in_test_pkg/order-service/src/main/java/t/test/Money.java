package t;
public class Money {
  private double price;          // BUG: float money
  private double balance;        // BUG
  public boolean same(Money o){ return Double.valueOf(price).equals(o.price); }
}
