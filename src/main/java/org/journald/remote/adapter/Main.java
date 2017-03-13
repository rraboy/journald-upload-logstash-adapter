package org.journald.remote.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;

public class Main
{
	private static final int SERVER_PORT = Integer.parseInt( System.getenv().getOrDefault( "SERVER_PORT", "9999" ) );

	private static final int CLIENT_PORT = Integer.parseInt( System.getenv().getOrDefault( "CLIENT_PORT", "9998" ) );

	private static final String CLIENT_HOST = System.getenv().getOrDefault( "CLIENT_HOST", "localhost" );

	private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger( Main.class );


	public static void main( final String[] args ) throws Exception
	{
		LOGGER.info( "serverPort {}", SERVER_PORT );
		LOGGER.info( "clientUrl http://{}:{}", CLIENT_HOST, CLIENT_PORT );
		final CloseableHttpClient client = HttpClients.createDefault();
		final ExecutorService clientExecutor = Executors.newFixedThreadPool( 2 );
		final Server server = new Server( SERVER_PORT );
		server.setHandler( new ParseHandler( client, clientExecutor ) );
		server.start();
		server.join();
	}

	public static class ParseHandler extends AbstractHandler
	{

		private final ObjectMapper objectMapper = new ObjectMapper();

		private final Splitter splitter = Splitter.on( '=' ).limit( 2 );

		private final CloseableHttpClient client;

		private final ExecutorService clientExecutor;

		public ParseHandler( final CloseableHttpClient client, final ExecutorService clientExecutor )
		{
			this.client = client;
			this.clientExecutor = clientExecutor;
		}

		@Override
		public void handle( final String target, final Request baseRequest, final HttpServletRequest request, final HttpServletResponse response ) throws IOException, ServletException
		{
			response.setContentType( "text/plain" );

			LOGGER.debug( "Headers:" );
			final Enumeration< String > hi = request.getHeaderNames();
			while( hi.hasMoreElements() )
			{
				final String name = hi.nextElement();
				LOGGER.debug( "\t{}: {}", name, request.getHeader( name ) );
			}

			if( !"application/vnd.fdo.journal".equals( request.getHeader( "Content-Type" ) ) )
			{
				LOGGER.warn( "invalid content-type received: {}", request.getHeader( "Content-Type" ) );
				response.setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
				response.getWriter().append( "Invalid content-type: Expecting application/vnd.fdo.journal " );
			}
			else
			{
				LOGGER.debug( "Body Received" );
				try ( InputStream stream = request.getInputStream() )
				{
					processBody( stream );
				}
				LOGGER.debug( "Body Done" );
				response.setStatus( HttpServletResponse.SC_OK );
			}
			baseRequest.setHandled( true );
		}

		private void processBody( final InputStream stream ) throws IOException
		{
			ObjectNode currentNode = null;
			String ln;

			while( true )
			{
				ln = getLine( stream );
				if( ln == null )
				{
					break;
				}
				else if( ln.isEmpty() )
				{
					// persists
					if( currentNode != null )
					{
						final String content = objectMapper.writeValueAsString( currentNode );
						LOGGER.info( content );

						clientExecutor.submit( () ->
						{
							final HttpPost uploadPost = new HttpPost( "http://" + CLIENT_HOST + ":" + CLIENT_PORT );
							uploadPost.setHeader( "Content-Type", "application/json" );
							uploadPost.setEntity( new StringEntity( content, StandardCharsets.UTF_8 ) );
							try
							{
								client.execute( uploadPost );
							}
							catch( final Exception e )
							{
								LOGGER.error( e );
							}
						} );

						currentNode = null;
					}
				}
				else
				{
					final List< String > fieldAndValue = splitter.splitToList( ln );

					if( currentNode == null )
					{
						currentNode = objectMapper.createObjectNode();
					}

					if( fieldAndValue.size() >= 2 )
					{
						// standard thing
						currentNode.put( fieldAndValue.get( 0 ), fieldAndValue.get( 1 ) );
					}
					else
					{
						final String value = getBinaryData( stream );
						if( value == null )
						{
							break;
						}
						currentNode.put( ln, value );
					}
				}
			}
		}

		private String getBinaryData( final InputStream stream ) throws IOException
		{
			final byte[] values = new byte[8];
			final int len = stream.read( values );
			if( len < 8 )
			{
				LOGGER.warn( "not enough bytes for binary size" );
				//premature
				return null;
			}
			ArrayUtils.reverse( values ); // network order to little endian
			final BigInteger binarySize = new BigInteger( values );
			LOGGER.info( "binarySize: {}", binarySize );

			final ByteArrayOutputStream buffer = new ByteArrayOutputStream( (int)binarySize.longValueExact() );
			for( int i = 0; i < binarySize.longValueExact(); i++ )
			{
				buffer.write( stream.read() );
			}

			final int newLineChar = stream.read();
			if( newLineChar != 0x0A )
			{
				// expecting newline
				LOGGER.warn( "expecting binary data new line but got {}", newLineChar );
				return null;
			}

			return new String( buffer.toByteArray(), 0, buffer.size(), StandardCharsets.UTF_8 );
		}

		private String getLine( final InputStream stream ) throws IOException
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream( 8192 );
			int b;
			while( ( b = stream.read() ) != -1 )
			{
				if( b == 0x0A )
				{
					return new String( buffer.toByteArray(), 0, buffer.size(), StandardCharsets.UTF_8 );
				}
				buffer.write( b );
			}
			if( b == -1 )
			{
				return null;
			}
			return "";
		}
	}
}
