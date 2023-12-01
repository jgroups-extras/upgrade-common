package org.jgroups.rolling_upgrades;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * @author Bela Ban
 * @since x.y
 */
public class TestClient {
    protected ManagedChannel                                channel;
    protected UpgradeServiceGrpc.UpgradeServiceStub         asyncStub;
    protected UpgradeServiceGrpc.UpgradeServiceBlockingStub blocking_stub;
    protected StreamObserver<Request>                       send_stream; // for sending of messages and join requests
    protected final Address                                 local_addr;
    protected View                                          view; // the current view
    protected static final String                           CLUSTER="upgrade-client";


    public TestClient(String addr) {
        local_addr=Address.newBuilder().setName(addr).build();
    }



    protected void start(int port) throws InterruptedException {
        channel=ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        asyncStub=UpgradeServiceGrpc.newStub(channel);
        blocking_stub=UpgradeServiceGrpc.newBlockingStub(channel);

        send_stream=asyncStub.connect(new StreamObserver<Response>() {
            public void onNext(Response rsp) {
                if(rsp.hasMessage()) {
                    handleMessage(rsp.getMessage());
                    return;
                }
                if(rsp.hasView()) {
                    handleView(rsp.getView());
                    return;
                }
                throw new IllegalStateException(String.format("response is illegal: %s", rsp));
            }

            public void onError(Throwable t) {
                System.out.printf("exception from server: %s\n", t);
            }

            public void onCompleted() {
                System.out.println("server is done");
            }
        });

        JoinRequest join_req=JoinRequest.newBuilder().setAddress(local_addr).setClusterName(CLUSTER).build();
        Request req=Request.newBuilder().setJoinReq(join_req).build();
        send_stream.onNext(req);

        BufferedReader in=new BufferedReader(new InputStreamReader(System.in));
        while(true) {
            try {
                System.out.print("> "); System.out.flush();
                String line=in.readLine();
                if(line.startsWith("quit") || line.startsWith("exit")) {
                    LeaveRequest lr=LeaveRequest.newBuilder().setClusterName(CLUSTER).setLeaver(local_addr).build();
                    req=Request.newBuilder().setLeaveReq(lr).build();
                    send_stream.onNext(req);
                    System.out.println("Client left gracefully");
                    break;
                }

                if(line.startsWith("dump")) {
                    DumpResponse response=blocking_stub.dump(Void.newBuilder().build());
                    System.out.printf("%s\n", response.getDump());
                    continue;
                }

                if(line.startsWith("unicast")) {
                    handleUnicast(line);
                    continue;
                }

                Message msg=Message.newBuilder().setClusterName(CLUSTER).setSender(local_addr)
                  .setPayload(ByteString.copyFrom(line.getBytes())).build();
                Request r=Request.newBuilder().setMessage(msg).build();
                send_stream.onNext(r);
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }

        send_stream.onCompleted();
    }

    protected void handleUnicast(String line) {
        int index=line.indexOf(':');
        if(index == -1)
            return;
        String member_name=line.substring("unicast ".length(), index);
        String message=line.substring(index+1).trim();
        Address dest=Address.newBuilder().setName(member_name).build();
        Message msg=Message.newBuilder().setClusterName(CLUSTER).setSender(local_addr).setDestination(dest)
          .setPayload(ByteString.copyFrom(message.getBytes())).build();
        Request r=Request.newBuilder().setMessage(msg).build();
        send_stream.onNext(r);
    }

    protected void handleView(View v) {
        System.out.printf("-- received view %s\n", Utils.print(v));
        this.view=v;
    }

    protected static void handleMessage(Message msg) {
        System.out.printf("received message from %s: %s\n", msg.getSender().getName(), new String(msg.getPayload().toByteArray()));
    }


    protected void stop() throws InterruptedException {
        channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws InterruptedException {
        TestClient client=new TestClient(args[0]);
        client.start(50051);
        client.stop();
    }
}
