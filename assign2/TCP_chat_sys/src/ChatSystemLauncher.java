public class ChatSystemLauncher
{
    public static void main(String[] args)
    {
        Thread serverThread = Thread.startVirtualThread(() -> {
            ChatServer server = new ChatServer(8080);
            server.start_server();
        });

        Thread botThread = Thread.startVirtualThread(() -> {
            try
            {
                Thread.sleep(3000); // Wait for server to start
                AIClient bot = new AIClient("localhost", 8080, "http://localhost:11434", "llama3", "general");
                bot.start();
                System.out.println("AI Bot connected");
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

        try
        {
            serverThread.join();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
}