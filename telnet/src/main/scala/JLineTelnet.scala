object JLineTelnet {
  import _root_.jline._
  import java.net._

  // see RFC854
  val IAC  = 255
  val DONT = 254
  val DO   = 253
  val WONT = 252
  val WILL = 251
  val SB   = 250
  val SE   = 240
  
  // RFC 857
  val ECHO = 1

  // RFC 1073
  val NAWS = 31

  import java.io.{InputStream, OutputStream}

  val myUnixTerminal = new UnixTerminal
  class Telnet(is: InputStream, os: OutputStream) extends InputStream {
    var width: Int = _
    var height: Int = _

    def write(bs: Int*) = {
      os.write(bs.map(_.toByte).toArray)
    }
    def readShort: Short = ((is.read << 8) + is.read).toShort
    def expect(bs: Int*) = {
      val buf = new Array[Byte](bs.size)
      val read = is.read(buf)
      if (read != bs.size) throw new RuntimeException("Expected "+bs.size+" bytes but got "+read)
      for ((a, b) <- bs zip buf)
        if (a.toByte != b) throw new RuntimeException("Expected "+a+" got "+b+" ["+buf.toSeq+"]")
    }
   
    def handleSubNeg {
      val opt = is.read

      println("Got SB for "+opt)

      def collectUntilSE {
	while(is.read != IAC){}
        expect(SE)
      }

      opt match {
        case NAWS => 
          width = readShort
          height = readShort
          println("Got width="+width+" height="+height)
          expect(IAC, SE)
        case _ => collectUntilSE
      }      
    }
    def handleDO(opt: Int): Unit = opt match {
      case ECHO => write(IAC, WILL, ECHO)
      case _    => println("Got (and ignored) DO "+opt)
    }

    override def read: Int = {
      val bt = is.read
      if (IAC != bt)
        bt
      else {
        val cmd = is.read

        def reportOption(name: String) {
          println(name+" "+is.read)
        }

        cmd match {
          case IAC => IAC
          case DO  => handleDO(is.read)
          case DONT=> reportOption("DONT")
          case WONT=> reportOption("WONT")
          case WILL=> reportOption("WILL")
          case SB  => handleSubNeg
          case SE  => reportOption("We missed an SE in handleSubNeg")
        }
        
        read
      }
    }

    object TelnetTerminal extends Terminal {
      override def readVirtualKey(is: InputStream): Int = myUnixTerminal.readVirtualKey(is)
      override def initializeTerminal(): Unit = {}
      override def getTerminalWidth: Int = width
      override def getTerminalHeight: Int = height
      override val isSupported = true
      override val getEcho = false
      override val isEchoEnabled = true
      override def enableEcho: Unit = {println("Should enable echo")}
      override def disableEcho: Unit = {println("Should disable echo")}
    }
  }

  def readerFromSocket(socket: ServerSocket): (ConsoleReader, OutputStream) = {
    val s = socket.accept

    val os = s.getOutputStream
    val is = new Telnet(s.getInputStream, os)    
     
    is.write(IAC, DO, NAWS)
    is.write(IAC, DONT, ECHO)
    
    (new ConsoleReader(is, new java.io.OutputStreamWriter(s.getOutputStream), null, is.TelnetTerminal) {
      override def getTermwidth: Int = is.width
      override def getTermheight: Int = is.height
    }, os)
  }
}
