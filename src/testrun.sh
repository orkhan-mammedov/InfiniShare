#!/bin/bash
rm -f {in,out}{1,2,3,4,5}
dd status=none if=/dev/urandom of=in1 bs=1000 count=5
dd status=none if=/dev/urandom of=in2 bs=1000 count=1500
dd status=none if=/dev/urandom of=in3 bs=1000 count=1
dd status=none if=/dev/urandom of=in4 bs=1000 count=2000
sync
./server &
sleep 1
./client localhost $(cat port) Gtstkey out1 199 &
./client localhost $(cat port) Gtstkey out2 500 &
./client localhost $(cat port) PtstkeyXX in4 500 2 &
./client localhost $(cat port) Ptstkey2 in3 500 0 &
./client localhost $(cat port) Ptstkey in1 500 10 &
./client localhost $(cat port) Ptstkey in2 500 2 &
./client localhost $(cat port) Gtstkey2 out3 200 &
./client localhost $(cat port) GtstkeyXX out4 500 &
sleep 1
./client localhost $(cat port) F
./client localhost $(cat port) Ptstkey3 1000 500 1 &
./client localhost $(cat port) Gtstkey3 out5 200 &
echo all started
wait
echo all finished
# check that out5 has not been created
[ -f out5 ] && [ $(stat -c "%s" out5) -gt 0 ] && { echo error: out5 created; exit 1; }
# re-arrange out1 and out2 (could be switched)
if [ $(stat -c "%s" out1) -gt $(stat -c "%s" out2) ]; then
	mv out1 outX
	mv out2 out1
	mv outX out2
fi
# compare
for i in 1 2 3 4; do
	diff in$i out$i
done