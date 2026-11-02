# Offline benchmarks

`classos_bench` (built with the C++ tests) measures the two algorithmic choices that can be
evaluated without users: timetable variable ordering, and grading-queue policies.

```bash
cd core && cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build build
./build/classos_bench
```

The output is deterministic apart from the times. **Re-run it on your own machine and
report that run**, with your hardware. The run below is a reference only.

## Reference run

Measured on: development sandbox, 1 vCPU (Intel Xeon, 2.80 GHz), GCC 13.3, `-O2`, October 2026.

```
Timetable generation: MRV ordering vs input-order baseline (3 s / 2M-node budget)
sections  teacher load   events ordering      placed  valid      nodes  backtracks      ms budget
10        1.6 t/section  175    MRV              175    yes        176           0      10 
10        1.6 t/section  175    input-order      175    yes        176           0       0 
20        1.6 t/section  350    MRV              350    yes        351           0      71 
20        1.6 t/section  350    input-order      350    yes        362          11       3 
10        0.8 t/section  175    MRV              175    yes        176           0       8 
10        0.8 t/section  175    input-order      175    yes       2828        2652       2 
20        0.8 t/section  350    MRV              350    yes        351           0      63 
20        0.8 t/section  350    input-order      350    yes    1258320     1257969    1109 
10        0.7 t/section  175    MRV              175    yes        176           0      11 
10        0.7 t/section  175    input-order      174    yes    2000000     1999825    1000 exhausted
20        0.7 t/section  350    MRV              350    yes        351           0      49 
20        0.7 t/section  350    input-order      348    yes    1891840     1891509    3000 exhausted

Grading scenario A: backlog plus urgent work (103 submissions; one teacher grading continuously from now)
policy                     avg wait h max wait h past grade-by  fairness
FCFS                             55.3       71.6            13     0.850
Priority, aging 0.00/h           57.5       82.2             0     0.629
Priority, aging 0.25/h           57.6       79.5             5     0.694
Priority, aging 1.00/h           55.4       71.6            13     0.848
Round Robin, quantum 1           56.7       77.1             0     0.596
Round Robin, quantum 3           56.7       77.1             0     0.607
Round Robin, quantum 10          56.7       77.1             0     0.650

Grading scenario B: two sections submit at the same time (80 submissions; one teacher grading continuously from now)
policy                     avg wait h max wait h past grade-by  fairness
FCFS                              8.7       15.8             0     0.943
Priority, aging 0.00/h            8.7       15.8             0     0.943
Priority, aging 0.25/h            8.7       15.8             0     0.943
Priority, aging 1.00/h            8.7       15.8             0     0.943
Round Robin, quantum 1            8.7       16.1             0     1.000
Round Robin, quantum 3            8.7       16.1             0     1.000
Round Robin, quantum 10           8.7       15.8             0     0.992
```

## What the results show

**Timetable ordering (MRV vs input order).** The deciding factor is teacher load, not size.
With light load (1.6 teachers per section) both orderings succeed without real search, and
MRV is slower because it re-counts every class's options at each step. With heavy load
(0.7–0.8 teachers per section, so each teacher has many courses), the input-order baseline
backtracks over a million times and, at 0.7, exhausts its budget without placing every
class; MRV places all of them with no backtracking in well under 100 ms. Conclusion: MRV costs
tens of milliseconds on easy instances and is the difference between success and failure
on tight ones. Report both halves.

**Grading scenario A (backlog plus urgent work).** FCFS returns all 13 urgent submissions
after their grade-by time. Priority without aging and Round Robin miss none. Aging trades
urgency for starvation-freedom: at 0.25 points/hour, 5 misses; at 1.0 points/hour the old
backlog overtakes the urgent work and Priority behaves like FCFS. FCFS has the highest
fairness index here only because the assignments arrived at very different times, so
"equal waiting" favours first-come order.

**Grading scenario B (two sections submit together).** Round Robin reaches a fairness
index of 1.000 against 0.943 for FCFS. Average wait is identical for every policy (8.7 h):
with equal grading times and no idle time, reordering cannot change the average wait, only
who waits. That is the expected theoretical result, and a good viva point.

**Limitations of these benchmarks.** The departments and queues are synthetic, the grader
is assumed to work without breaks, and grading times are fixed estimates. Validate the
conclusions on real data during the trial (`docs/evaluation-plan.md`).
