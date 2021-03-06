package net.virtualvoid.scala.tools

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

  // RFC 858
  val SUPPRESS_GO_AHEAD = 3

  // RFC 1073
  val NAWS = 31

  // RFC 1184
  val LINEMODE = 34
  val MODE = 1
  val MASK_EDIT = 1
  val MASK_LIT_ECHO = 16
  val FORWARDMASK = 2
  val SLC = 3
  val SLC_VALUE = 2
  val SLC_FORW2 = 18

  import java.io.{InputStream, OutputStream}

  val myUnixTerminal = new UnixTerminal
  /**
   * Wraps the InputStream to catch and handle telnet control sequences.
   */
  class Telnet(is: InputStream, os: OutputStream) extends InputStream {
    // the terminal's height and width as we got them from NAWS
    var width: Int = _
    var height: Int = _

    // auxillary functions
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
   
    /**
     * Very basic handling of SB. Handles only NAWS and
     * shows debugging messages.
     */
    def handleSubNeg {
      val opt = is.read

      println("Got SB for "+opt)

      def collectUntilSE {
	while(is.read != IAC){}
        is.read match {
          // don't stop reading, this is just escaped IAC
          case IAC    => collectUntilSE
          case SE     => // that's correct
          case x:Byte => throw new RuntimeException("got "+x+" when expecting SE")
        }
      }

      opt match {
        case NAWS => 
          width = readShort
          height = readShort
          println("Got width="+width+" height="+height)
          expect(IAC, SE)
        case LINEMODE =>
	  val func = is.read
          println("Got SB LINEMODE "+func)
          func match {
            case MODE => println("Got mode mask "+is.read)
            case _    => 
          }
          collectUntilSE
        case _ => collectUntilSE
      }      
    }
    /**
     * In case, someone asks for echoing: tell them, we do
     */
    def handleDO(opt: Int): Unit = opt match {
      case ECHO => write(IAC, WILL, ECHO)
      case SUPPRESS_GO_AHEAD => write(IAC, WILL, SUPPRESS_GO_AHEAD)
      case _    => println("Got (and ignored) DO "+opt)
    }

    override def read: Int = {
      val bt = is.read
      if (IAC != bt)
        bt
      else {
        val cmd = is.read

        def reportOption(name: String) {
          println("Got "+name+" "+is.read)
        }

        cmd match {
          case IAC => return IAC
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

    /** The JLine Terminal implementation */
    object TelnetTerminal extends Terminal {
      // use the standard ANSI handling
      override def readVirtualKey(is: InputStream): Int = myUnixTerminal.readVirtualKey(is)
      override def initializeTerminal(): Unit = {}
      override def getTerminalWidth: Int = width
      override def getTerminalHeight: Int = height
      override val isSupported = true
      override val getEcho = false
      
      override val isEchoEnabled = true // method isEchoEnabled is never called from JLine
      override def enableEcho: Unit = {println("Should enable echo")}
      override def disableEcho: Unit = {println("Should disable echo")}
    }
  }
  
  def osWithCR(os: OutputStream): OutputStream = new OutputStream {
    var last: Int = _
    override def write(b: Int) = {
      if (b == '\n' && last != '\r')
        os.write('\r')
      
      last = b
      os.write(b)
    }
  }

  def readerFromSocket(s: Socket): (ConsoleReader, OutputStream) = {
    val os = osWithCR(s.getOutputStream)
    val is = new Telnet(s.getInputStream, os)    
    
    // request WindowSize handling
    is.write(IAC, DO, NAWS)
    // don't wait for GO_AHEAD before doing anything
    is.write(IAC, WILL, SUPPRESS_GO_AHEAD)
    // jline will echo all visible characters
    is.write(IAC, WILL, ECHO)
    
    (new ConsoleReader(is, new java.io.OutputStreamWriter(os), null, is.TelnetTerminal) {
      // the default implementation instantiates a new default Terminal to find out... stupid
      override def getTermwidth: Int = is.width
      override def getTermheight: Int = is.height
    }, os)
  }
}

