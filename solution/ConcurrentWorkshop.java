package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ConcurrentWorkshop implements Workshop {
    private Map<Long, ConcurrentWorkplace> userWorkplace;
    private Map<ConcurrentWorkplace, Long> occupiedBy;
    private Map<ConcurrentWorkplace, Integer> someoneWaitingFor;
    private Map<Long, ConcurrentWorkplace> wantsToGo;
    private Map<WorkplaceId, ConcurrentWorkplace> workplaceId;
    private Map<Long, Long> nextInCycle;
    private Map<Long, Semaphore> isWaiting;
    private Semaphore mutex;
    private Semaphore starvationGuard;
    private int leaveCounter;
    private Deque<Long> waitingQueue;


    public ConcurrentWorkshop(Collection<Workplace> workplaces) {
        userWorkplace = new ConcurrentHashMap<>();
        occupiedBy = new ConcurrentHashMap<>();
        someoneWaitingFor = new ConcurrentHashMap<>();
        wantsToGo = new ConcurrentHashMap<>();
        workplaceId = new ConcurrentHashMap<>();
        nextInCycle = new ConcurrentHashMap<>();
        isWaiting = new ConcurrentHashMap<>();
        mutex = new Semaphore(1, true);
        starvationGuard = new Semaphore(workplaces.size(), true);
        leaveCounter = 0;
        waitingQueue = new ConcurrentLinkedDeque<>();
        for (var workplace : workplaces) {
            workplaceId.put(workplace.getId(), new ConcurrentWorkplace(workplace.getId(), workplace));
        }

    }

    private boolean checkIfCanEnter() {
        if (waitingQueue.isEmpty()) {
            return false;
        }
        Long uid = waitingQueue.peek();
        Long cur = uid;
        while (wantsToGo.containsKey(cur) && occupiedBy.containsKey(wantsToGo.get(cur))) {
            cur = occupiedBy.get(wantsToGo.get(cur));
        }
        if (!wantsToGo.containsKey(cur)) {
            return false;
        }
        cur = uid;
        Long prev = uid;
        CountDownLatch latch;
        while (occupiedBy.containsKey(wantsToGo.get(cur))) {
            latch = new CountDownLatch(1);
            cur = occupiedBy.get(wantsToGo.get(cur));
            wantsToGo.get(prev).setPresentGuy(latch);
            wantsToGo.get(cur).setLeftGuy(latch);
            nextInCycle.put(cur, prev);
            prev = cur;
        }
        isWaiting.get(cur).release();
        return true;
    }

    private void setSomeoneWaitingFor(ConcurrentWorkplace workplace, Integer integer) {
        if (integer > 0) {
            if (!someoneWaitingFor.containsKey(workplace)) {
                someoneWaitingFor.put(workplace, 0);
            }
            someoneWaitingFor.put(workplace, someoneWaitingFor.get(workplace) + 1);
        } else {
            someoneWaitingFor.put(workplace, someoneWaitingFor.get(workplace) - 1);
            if (someoneWaitingFor.get(workplace).equals(0)) {
                someoneWaitingFor.remove(workplace);
            }
        }
    }

    @Override
    public Workplace enter(WorkplaceId wid) {
        try {
            starvationGuard.acquire();
            mutex.acquire();
            Long uid = Thread.currentThread().getId();
            ConcurrentWorkplace newWorkplace = workplaceId.get(wid);
            if (occupiedBy.containsKey(newWorkplace) || someoneWaitingFor.containsKey(newWorkplace)) {
                wantsToGo.put(uid, newWorkplace);
                isWaiting.put(uid, new Semaphore(0, true));
                waitingQueue.add(uid);
                setSomeoneWaitingFor(newWorkplace, 1);
                mutex.release();
                isWaiting.get(uid).acquire();
                setSomeoneWaitingFor(newWorkplace, -1);
                if (nextInCycle.containsKey(uid)) {
                    return finnishCycle(uid, newWorkplace);
                }
                wantsToGo.remove(uid);
            }
            occupiedBy.put(newWorkplace, uid);
            userWorkplace.put(uid, newWorkplace);
            waitingQueue.remove(uid);
            if (!checkIfCanEnter()) {
                mutex.release();
            }
            return newWorkplace;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean findCycle(Long uid) {
        Long next = occupiedBy.get(wantsToGo.get(uid));
        int length = 1;
        while (!next.equals(uid) && wantsToGo.containsKey(next) && occupiedBy.get(wantsToGo.get(next)) != null) {
            next = occupiedBy.get(wantsToGo.get(next));
            length++;
        }
        if (!next.equals(uid)) {
            return false;
        }
        Long prev = uid;
        int i = 0;
        CountDownLatch latch = new CountDownLatch(length);
        do {
            next = occupiedBy.get(wantsToGo.get(next));
            nextInCycle.put(prev, next);
            userWorkplace.get(prev).setPresentGuy(latch);
            userWorkplace.get(prev).setLeftGuy(latch);
            prev = next;
            i++;
        } while (i < length);
        return true;
    }

    @Override
    public Workplace switchTo(WorkplaceId wid) {
        try {
            mutex.acquire();
            Long uid = Thread.currentThread().getId();
            ConcurrentWorkplace newWorkplace = workplaceId.get(wid);
            if (occupiedBy.containsKey(newWorkplace)) {
                wantsToGo.put(uid, newWorkplace);
                isWaiting.put(uid, new Semaphore(0, true));
                waitingQueue.add(uid);
                if (findCycle(uid)) {
                    return finnishCycle(uid, newWorkplace);
                } else {
                    setSomeoneWaitingFor(newWorkplace, 1);
                    if (!checkIfCanEnter()) {
                        mutex.release();
                    }
                    isWaiting.get(uid).acquire();
                    setSomeoneWaitingFor(newWorkplace, -1);
                    if (nextInCycle.containsKey(uid)) {
                        return finnishCycle(uid, newWorkplace);
                    }
                }
            }
            wantsToGo.remove(uid);
            ConcurrentWorkplace oldWorkplace = userWorkplace.remove(uid);
            occupiedBy.remove(oldWorkplace);
            userWorkplace.put(uid, newWorkplace);
            occupiedBy.put(newWorkplace, uid);
            CountDownLatch latch = new CountDownLatch(1);
            oldWorkplace.setPresentGuy(latch);
            newWorkplace.setLeftGuy(latch);
            isWaiting.remove(uid);
            waitingQueue.remove(uid);
            if (!checkIfCanEnter()) {
                mutex.release();
            }
            return newWorkplace;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private Workplace finnishCycle(Long uid, ConcurrentWorkplace newWorkplace) {
        occupiedBy.put(newWorkplace, uid);
        userWorkplace.put(uid, wantsToGo.get(uid));
        wantsToGo.remove(uid);
        waitingQueue.remove(uid);
        isWaiting.remove(uid);
        if (isWaiting.containsKey(nextInCycle.get(uid))) {
            isWaiting.get(nextInCycle.get(uid)).release();
        } else {
            if (!checkIfCanEnter()) {
                mutex.release();
            }
        }
        nextInCycle.remove(uid);
        return newWorkplace;
    }

    @Override
    public void leave() {
        try {
            mutex.acquire();
            Long uid = Thread.currentThread().getId();
            ConcurrentWorkplace workplace = userWorkplace.get(uid);
            occupiedBy.remove(workplace);
            userWorkplace.remove(uid);
            leaveCounter++;
            if (leaveCounter == workplaceId.size()) {
                leaveCounter = 0;
                starvationGuard.release(workplaceId.size());
            }
            if (!checkIfCanEnter()) {
                mutex.release();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}