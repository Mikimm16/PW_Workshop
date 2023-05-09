package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;

import java.util.concurrent.CountDownLatch;


public class ConcurrentWorkplace extends Workplace {
    private final Workplace workplace;
    private CountDownLatch leftGuy;
    private CountDownLatch presentGuy;


    public ConcurrentWorkplace(WorkplaceId id, Workplace workplace) {
        super(id);
        this.workplace = workplace;
        leftGuy = new CountDownLatch(0);
        presentGuy = new CountDownLatch(0);
    }

    public void setPresentGuy(CountDownLatch presentGuy) {
        this.presentGuy = presentGuy;
    }

    public void setLeftGuy(CountDownLatch leftGuy) {
        this.leftGuy = leftGuy;
    }

    @Override
    public void use() {
        try {
            leftGuy.countDown();
            presentGuy.await();
            workplace.use();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
